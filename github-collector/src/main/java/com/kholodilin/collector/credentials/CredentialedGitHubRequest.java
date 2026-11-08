package com.kholodilin.collector.credentials;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
/** Execution primitive for #215. A real limiter gate must be supplied by #214; there is no bypass bean. */
public final class CredentialedGitHubRequest {
    public record QuotaKey(String origin,String credentialRef) { }
    public interface Permit extends AutoCloseable {@Override void close();}
    @FunctionalInterface public interface RequestGate {Permit acquire(QuotaKey key);}
    @FunctionalInterface public interface Call<T> {T execute(String token);}
    public static final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status) {super("GitHub request failed (HTTP "+status+")");this.status=status;}
        public int status() {return status;}
    }
    private final CredentialCache cache;private final RequestGate gate;
    public CredentialedGitHubRequest(CredentialCache cache,RequestGate gate) {this.cache=cache;this.gate=gate;}
    public <T> T execute(String origin,JobRequest job,Call<T> call) {
        CredentialLease lease=cache.get(origin,job);
        try {return invoke(origin,lease,call);}
        catch(Failure error) {
            if(error.status()!=401) throw error;
            lease=cache.refreshRejected(origin,job,lease);
            try {return invoke(origin,lease,call);}
            catch(Failure second) {if(second.status()==401) cache.reject(origin,lease);throw second;}
        }
    }
    private <T> T invoke(String origin,CredentialLease lease,Call<T> call) {
        try(Permit permit=gate.acquire(new QuotaKey(origin,lease.credentialRef()))) {return call.execute(lease.token());}
        catch(Failure error) {throw error;}
        catch(RuntimeException error) {throw new Failure(0);}
    }
}
