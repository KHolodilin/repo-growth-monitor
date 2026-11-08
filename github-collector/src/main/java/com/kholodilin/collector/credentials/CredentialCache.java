package com.kholodilin.collector.credentials;
import com.github.benmanes.caffeine.cache.*;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Per-process bounded authorization mapping + separately bounded secret cache. Reads never extend leases. */
public final class CredentialCache {
    public record SecretKey(String origin,String context,String ref,String version) { }
    private record ProjectKey(String origin,String githubId,String context,String ref) { }
    private record ContextKey(String origin,String context) { }
    private record Mapping(SecretKey secret,Instant until) { }
    private static final class VersionChanged extends RuntimeException { }
    private record Secret(CredentialLease lease,Instant until) {@Override public String toString(){return "Secret[REDACTED]";}}
    private final Cache<ProjectKey,Mapping> projects;
    private final Cache<SecretKey,Secret> secrets;
    private final Set<SecretKey> rejected=ConcurrentHashMap.newKeySet();
    private final Set<ContextKey> revoked=ConcurrentHashMap.newKeySet();
    private volatile boolean quarantined;
    private final CredentialResolver resolver;private final CollectorCredentialProperties properties;private final Clock clock;
    public CredentialCache(CredentialResolver resolver,CollectorCredentialProperties properties,Clock clock,Ticker ticker) {
        this.resolver=resolver;this.properties=properties;this.clock=clock;
        projects=Caffeine.newBuilder().maximumSize(properties.maximumSize()).ticker(ticker).expireAfter(new Expiry<ProjectKey,Mapping>() {
            public long expireAfterCreate(ProjectKey key,Mapping value,long now){return remaining(value.until());}
            public long expireAfterUpdate(ProjectKey key,Mapping value,long now,long current){return remaining(value.until());}
            public long expireAfterRead(ProjectKey key,Mapping value,long now,long current){return current;}
        }).build();
        secrets=Caffeine.newBuilder().maximumSize(properties.maximumSize()).ticker(ticker).expireAfter(new Expiry<SecretKey,Secret>() {
            public long expireAfterCreate(SecretKey key,Secret value,long now){return remaining(value.until());}
            public long expireAfterUpdate(SecretKey key,Secret value,long now,long current){return remaining(value.until());}
            public long expireAfterRead(SecretKey key,Secret value,long now,long current){return current;}
        }).build();
    }
    private long remaining(Instant until) {return Math.max(0,Duration.between(clock.instant(),until).toNanos());}
    private ProjectKey project(String origin,JobRequest job) {
        if(origin==null || origin.isBlank() || job.authorization()==null || !job.deadlineAt().isAfter(clock.instant())) throw new CredentialUnavailableException();
        return new ProjectKey(origin,job.repository().githubId(),job.authorization().context(),job.authorization().credentialRef());
    }
    private SecretKey key(String origin,CredentialLease lease) {return new SecretKey(origin,lease.authorizationContext(),lease.credentialRef(),lease.credentialVersion());}
    private void allowed(ProjectKey project) {
        if(quarantined || revoked.contains(new ContextKey(project.origin(),project.context()))) throw new CredentialUnavailableException();
    }
    public CredentialLease get(String origin,JobRequest job) {
        ProjectKey project=project(origin,job);allowed(project);
        // Eviction of a secret may leave a harmless mapping: reload through the authority rather than guessing.
        for(int i=0;i<2;i++) {
            Mapping mapping=projects.get(project,p->load(p,job));allowed(project);
            if(mapping.secret()==null) throw new CredentialUnavailableException();
            Secret secret=secrets.getIfPresent(mapping.secret());
            if(secret==null) {
                try {
                    secret=secrets.get(mapping.secret(),key->{
                        Secret fresh=fetch(project,job);
                        if(!key.equals(key(origin,fresh.lease()))) throw new VersionChanged();
                        return fresh;
                    });
                } catch(VersionChanged changed) {projects.asMap().remove(project,mapping);continue;}
                catch(RuntimeException failed) {projects.put(project,new Mapping(null,clock.instant().plus(properties.failureBackoff())));throw new CredentialUnavailableException();}
            }
            if(secret!=null && secret.until().isAfter(clock.instant()) && !rejected.contains(mapping.secret())) return secret.lease();
            projects.asMap().remove(project,mapping);
        }
        throw new CredentialUnavailableException();
    }
    private Mapping load(ProjectKey project,JobRequest job) {
        try {
            Secret secret=fetch(project,job);SecretKey key=key(project.origin(),secret.lease());
            secrets.put(key,secret);return new Mapping(key,secret.until());
        } catch(RuntimeException error) {return new Mapping(null,clock.instant().plus(properties.failureBackoff()));}
    }
    private Secret fetch(ProjectKey project,JobRequest job) {
        try {
            allowed(project);
            CredentialLease lease=resolver.resolve(project.origin(),new CredentialRequest(1,job.jobId(),job.attemptId()));
            if(!project.context().equals(lease.authorizationContext()) || !project.ref().equals(lease.credentialRef())) throw new CredentialUnavailableException();
            if(rejected.contains(key(project.origin(),lease))) throw new CredentialUnavailableException();
            Instant until=clock.instant().plus(properties.ttl());if(until.isAfter(lease.cacheValidUntil())) until=lease.cacheValidUntil();
            if(lease.expiresAt()!=null && until.isAfter(lease.expiresAt())) until=lease.expiresAt();
            if(until.isAfter(job.deadlineAt())) until=job.deadlineAt();if(!until.isAfter(clock.instant())) throw new CredentialUnavailableException();
            allowed(project);return new Secret(lease,until);
        } catch(RuntimeException error) {throw new CredentialUnavailableException();}
    }
    /** A stale 401 invalidates only the rejected version, never a newer credential. */
    public synchronized void reject(String origin,CredentialLease lease) {
        SecretKey key=key(origin,lease);
        if(!rejected.contains(key) && rejected.size()+revoked.size()>=properties.maximumRejected()) quarantined=true;
        else rejected.add(key);
        secrets.invalidate(key);projects.asMap().entrySet().removeIf(e->key.equals(e.getValue().secret()));
    }
    public CredentialLease refreshRejected(String origin,JobRequest job,CredentialLease rejectedLease) {reject(origin,rejectedLease);return get(origin,job);}
    /** Explicit service-authenticated revocation; a rebind must issue a new authorization context. */
    public synchronized void invalidateContext(String origin,String context) {
        ContextKey key=new ContextKey(origin,context);
        if(!revoked.contains(key) && rejected.size()+revoked.size()>=properties.maximumRejected()) quarantined=true;
        else revoked.add(key);
        projects.asMap().keySet().removeIf(p->p.origin().equals(origin) && p.context().equals(context));
        secrets.asMap().keySet().removeIf(k->k.origin().equals(origin) && k.context().equals(context));
    }
    public long mappingSize() {projects.cleanUp();return projects.estimatedSize();}
    public long secretSize() {secrets.cleanUp();return secrets.estimatedSize();}
}
