package com.kholodilin.repogrowth.credentials;

import com.kholodilin.collector.contracts.CollectorProtocol.CredentialRequest;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Central, durable quota authority. A node restart never replenishes the shared budget. */
@Service
@ConditionalOnProperty(name="rgm.credentials.enabled",havingValue="true")
public class SharedGitHubBudget {
    public record Acquisition(UUID requestId,CredentialRequest assignment,String resource) { }
    public record Grant(UUID permitId,Instant leasedUntil,Instant retryAt) { }
    public record Report(UUID permitId,int status,Long remaining,Instant resetAt,Instant retryAt) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CredentialVault vault;
    private final GitHubBudgetProperties properties;
    public SharedGitHubBudget(JdbcTemplate jdbc,PlatformTransactionManager tm,CredentialVault vault) {
        this(jdbc,tm,vault,new GitHubBudgetProperties(0,0,0,0,null));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SharedGitHubBudget(JdbcTemplate jdbc,PlatformTransactionManager tm,CredentialVault vault,GitHubBudgetProperties properties) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(tm);this.vault=vault;this.properties=properties;
    }
    private static Timestamp ts(Instant time){return Timestamp.from(time);}
    public Grant acquire(String node,Acquisition request) {
        if(request.requestId()==null || !Set.of("core","search").contains(request.resource())) throw new IllegalArgumentException("Invalid quota request");
        return tx.execute(status->{
            String identity=vault.resolve(node,request.assignment()).credentialRef();
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,identity);
            Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
            var previous=jdbc.queryForList("SELECT * FROM github_request_permit WHERE permit_id=?",request.requestId());
            if(!previous.isEmpty()) {
                var row=previous.getFirst();
                if(!node.equals(row.get("node_key")) || !request.assignment().attemptId().equals(row.get("attempt_id")) || !identity.equals(row.get("identity_key")) || !request.resource().equals(row.get("resource"))) throw new IllegalArgumentException("Permit identity conflict");
                Instant until=((Timestamp)row.get("leased_until")).toInstant();return new Grant(request.requestId(),until,until.isAfter(now)?null:until);
            }
            boolean search=request.resource().equals("search");long ceiling=search?properties.searchPerMinute():properties.coreHourly();long window=search?60:3600;
            jdbc.update("INSERT INTO github_budget(identity_key,resource,remaining,reset_at,next_request_at) VALUES (?,?,?, ?,?) ON CONFLICT DO NOTHING",identity,request.resource(),ceiling,ts(now.plusSeconds(window)),ts(now));
            var budget=jdbc.queryForMap("SELECT * FROM github_budget WHERE identity_key=? AND resource=? FOR UPDATE",identity,request.resource());
            Instant reset=((Timestamp)budget.get("reset_at")).toInstant();long remaining=((Number)budget.get("remaining")).longValue();
            if(!reset.isAfter(now)){remaining=ceiling;reset=now.plusSeconds(window);}
            Instant retry=((Timestamp)budget.get("next_request_at")).toInstant();
            if(budget.get("cooldown_until")!=null){Instant cooldown=((Timestamp)budget.get("cooldown_until")).toInstant();if(cooldown.isAfter(retry))retry=cooldown;}
            var active=jdbc.queryForList("SELECT leased_until FROM github_request_permit WHERE identity_key=?",Timestamp.class,identity);
            if(!active.isEmpty() && active.getFirst().toInstant().isAfter(retry))retry=active.getFirst().toInstant();
            if(remaining<=(search?properties.searchReserve():properties.coreReserve()) && reset.isAfter(retry))retry=reset;
            if(retry.isAfter(now))return new Grant(null,null,retry);
            jdbc.update("DELETE FROM github_request_permit WHERE identity_key=? AND leased_until<=?",identity,ts(now));
            Instant until=now.plus(properties.permitLease());
            jdbc.update("UPDATE github_budget SET remaining=?,reset_at=?,next_request_at=?,updated_at=? WHERE identity_key=? AND resource=?",remaining-1,ts(reset),ts(now.plusMillis((window*1000+ceiling-1)/ceiling)),ts(now),identity,request.resource());
            jdbc.update("INSERT INTO github_request_permit(permit_id,identity_key,node_key,attempt_id,resource,generation,leased_until) VALUES (?,?,?,?,?,1,?)",request.requestId(),identity,node,request.assignment().attemptId(),request.resource(),ts(until));
            return new Grant(request.requestId(),until,null);
        });
    }
    public boolean report(String node,Report report) {
        return Boolean.TRUE.equals(tx.execute(status->{
            var identities=jdbc.queryForList("SELECT identity_key FROM github_request_permit WHERE permit_id=? AND node_key=?",String.class,report.permitId(),node);
            if(identities.isEmpty())return false;
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,identities.getFirst());
            var rows=jdbc.queryForList("SELECT * FROM github_request_permit WHERE permit_id=? AND node_key=? FOR UPDATE",report.permitId(),node);
            if(rows.isEmpty())return false;var row=rows.getFirst();String identity=(String)row.get("identity_key"),resource=(String)row.get("resource");
            // Out-of-order responses may only lower remaining or extend protective waits.
            if(report.remaining()!=null && report.remaining()>=0)jdbc.update("UPDATE github_budget SET remaining=least(remaining,?) WHERE identity_key=? AND resource=?",report.remaining(),identity,resource);
            if(report.resetAt()!=null)jdbc.update("UPDATE github_budget SET reset_at=greatest(reset_at,?) WHERE identity_key=? AND resource=?",ts(report.resetAt()),identity,resource);
            Instant cooldown=report.retryAt();
            if((report.status()==403 || report.status()==429) && cooldown==null)cooldown=Instant.now().plusSeconds(60);
            if(report.status()==401 || report.status()==404)cooldown=Instant.now().plusSeconds(30);
            if(cooldown!=null)jdbc.update("UPDATE github_budget SET cooldown_until=greatest(cooldown_until,?) WHERE identity_key=?",ts(cooldown),identity);
            jdbc.update("INSERT INTO github_budget_receipt(permit_id) VALUES (?) ON CONFLICT DO NOTHING",report.permitId());
            jdbc.update("DELETE FROM github_request_permit WHERE permit_id=?",report.permitId());return true;
        }));
    }
}
