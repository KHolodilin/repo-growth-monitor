package com.kholodilin.repogrowth.credentials;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.repogrowth.common.api.*;
import com.kholodilin.repogrowth.common.api.ErrorCode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(name="rgm.credentials.enabled",havingValue="true")
public class CredentialVault {
    public record Metadata(String ref,long version,boolean revoked,Instant expiresAt,String quotaIdentity) { }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final CredentialEncryption encryption;private final CredentialVaultProperties properties;private final Clock clock;
    public CredentialVault(JdbcTemplate jdbc,PlatformTransactionManager tm,CredentialVaultProperties properties,Clock clock) {
        this.jdbc=jdbc;this.tx=new TransactionTemplate(tm);this.properties=properties;this.clock=clock;encryption=new CredentialEncryption(properties);
    }
    private ApiException denied() {return new ApiException(ErrorCode.FORBIDDEN,HttpStatus.FORBIDDEN,"Credential assignment is not authorized");}
    public Metadata rotate(String ref,String token,Instant expiresAt) {return rotate(ref,token,expiresAt,null);}
    public Metadata rotate(String ref,String token,Instant expiresAt,String quotaIdentity) {
        if(quotaIdentity!=null && !quotaIdentity.matches("[A-Za-z0-9_.:-]{1,128}"))throw ApiException.validation("Invalid quota identity");
        if(ref==null || !ref.matches("[A-Za-z0-9_-]{1,128}") || token==null || token.isBlank() || token.length()>4096 || token.chars().anyMatch(c->c<=32 || c==127)) throw ApiException.validation("Invalid credential input");
        final Instant storedExpiry=expiresAt==null?null:expiresAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(storedExpiry!=null && !storedExpiry.isAfter(clock.instant())) throw ApiException.validation("Token expiry must be in the future");
        return tx.execute(status->{
            jdbc.update("INSERT INTO github_credential(ref,current_version) VALUES (?,0) ON CONFLICT(ref) DO NOTHING",ref);
            var row=jdbc.queryForMap("SELECT id,current_version,quota_identity FROM github_credential WHERE ref=? FOR UPDATE",ref);
            long id=((Number)row.get("id")).longValue(),version=((Number)row.get("current_version")).longValue()+1;
            var encrypted=encryption.encrypt(ref,version,token);
            jdbc.update("INSERT INTO github_credential_version(credential_id,version,key_id,nonce,ciphertext,expires_at) VALUES (?,?,?,?,?,?)",id,version,encrypted.keyId(),encrypted.nonce(),encrypted.ciphertext(),storedExpiry==null?null:Timestamp.from(storedExpiry));
            jdbc.update("UPDATE github_credential SET current_version=?,revoked=false,updated_at=clock_timestamp() WHERE id=?",version,id);
            String identity=quotaIdentity==null?(String)row.get("quota_identity"):quotaIdentity;
            jdbc.update("UPDATE github_credential SET quota_identity=? WHERE id=?",identity,id);
            return new Metadata(ref,version,false,storedExpiry,identity);
        });
    }
    public AuthorizationScope bind(long repositoryId,String ref) {
        return tx.execute(status->{
            if(jdbc.queryForList("SELECT id FROM repository WHERE id=? FOR UPDATE",Long.class,repositoryId).isEmpty()) throw ApiException.notFound("Repository not found");
            var rows=jdbc.queryForList("SELECT id FROM github_credential WHERE ref=? AND NOT revoked FOR SHARE",Long.class,ref);
            if(rows.isEmpty()) throw ApiException.notFound("Active credential not found");
            UUID context=UUID.randomUUID();
            jdbc.update("INSERT INTO repository_credential(repository_id,credential_id,authorization_context) VALUES (?,?,?) ON CONFLICT(repository_id) DO UPDATE SET credential_id=excluded.credential_id,authorization_context=excluded.authorization_context,updated_at=clock_timestamp()",repositoryId,rows.getFirst(),context);
            return new AuthorizationScope(context.toString(),ref);
        });
    }
    public boolean revoke(String ref) {return jdbc.update("UPDATE github_credential SET revoked=true,updated_at=clock_timestamp() WHERE ref=?",ref)==1;}
    public AuthorizationScope scope(long repositoryId) {
        var rows=jdbc.queryForList("SELECT b.authorization_context,c.ref FROM repository_credential b JOIN github_credential c ON c.id=b.credential_id WHERE b.repository_id=? AND NOT c.revoked",repositoryId);
        if(rows.isEmpty()) throw new ApiException(ErrorCode.INTERNAL_ERROR,HttpStatus.SERVICE_UNAVAILABLE,"Repository credential is not configured");
        return new AuthorizationScope(rows.getFirst().get("authorization_context").toString(),(String)rows.getFirst().get("ref"));
    }
    public CredentialLease resolve(String node,CredentialRequest request) {
        return tx.execute(status->{
            jdbc.queryForList("SELECT r.id FROM collection_request r JOIN collection_task t ON t.request_id=r.id WHERE t.public_id=? FOR SHARE OF r",Long.class,request.jobId());
            jdbc.queryForList("SELECT id FROM collection_task WHERE public_id=? FOR SHARE",Long.class,request.jobId());
            var rows=jdbc.queryForList("""
                SELECT t.authorization_context,c.ref,c.quota_identity,c.current_version,v.key_id,v.nonce,v.ciphertext,v.expires_at,d.deadline_at
                FROM collection_task t JOIN collection_attempt a ON a.task_id=t.id AND a.public_id=t.current_attempt_id
                JOIN collector_node n ON n.id=a.node_id JOIN collector_dispatch d ON d.attempt_id=a.public_id
                JOIN collection_request r ON r.id=t.request_id
                JOIN repository_credential b ON b.repository_id=t.repository_id AND b.authorization_context=t.authorization_context
                JOIN github_credential c ON c.id=b.credential_id AND c.ref=t.credential_ref
                JOIN github_credential_version v ON v.credential_id=c.id AND v.version=c.current_version
                WHERE t.public_id=? AND a.public_id=? AND n.node_key=? AND n.enabled AND NOT c.revoked AND r.state='ACTIVE'
                  AND a.state IN ('ASSIGNED','ACCEPTED','RUNNING') AND t.state IN ('READY','ACCEPTED','RUNNING','RETRY')
                FOR SHARE OF a,t,r,n,b,c,v
                """,request.jobId(),request.attemptId(),node);
            if(rows.isEmpty()) throw denied();var row=rows.getFirst();Instant now=clock.instant();
            Instant deadline=((Timestamp)row.get("deadline_at")).toInstant();
            Instant expiry=row.get("expires_at")==null?null:((Timestamp)row.get("expires_at")).toInstant();
            Instant until=now.plus(properties.cacheLease());if(until.isAfter(deadline)) until=deadline;if(expiry!=null && until.isAfter(expiry)) until=expiry;
            if(!until.isAfter(now)) throw denied();
            String ref=(String)row.get("ref");long version=((Number)row.get("current_version")).longValue();
            String token=encryption.decrypt(ref,version,new CredentialEncryption.Encrypted((String)row.get("key_id"),(byte[])row.get("nonce"),(byte[])row.get("ciphertext")));
            return new CredentialLease(1,row.get("authorization_context").toString(),ref,Long.toString(version),token,expiry,until,(String)row.get("quota_identity"));
        });
    }
}
