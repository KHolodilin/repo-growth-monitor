package com.kholodilin.repogrowth.credentials;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name="rgm.credentials.enabled",havingValue="true")
public class CredentialController {
    public record Rotation(String token,Instant expiresAt,String quotaIdentity) {public Rotation(String token,Instant expiresAt){this(token,expiresAt,null);}@Override public String toString(){return "Rotation[REDACTED]";}}
    public record Binding(String ref) { }
    private final CredentialVault vault;
    public CredentialController(CredentialVault vault) {this.vault=vault;}
    @PostMapping("/internal/v1/credentials/resolve")
    public ResponseEntity<CredentialLease> resolve(@RequestBody CredentialRequest request,Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").body(vault.resolve(authentication.getName(),request));
    }
    @PutMapping("/api/v1/admin/credentials/{ref}")
    public CredentialVault.Metadata rotate(@PathVariable String ref,@RequestBody Rotation rotation) {return vault.rotate(ref,rotation.token(),rotation.expiresAt(),rotation.quotaIdentity());}
    @PutMapping("/api/v1/admin/repositories/{id}/credential")
    public AuthorizationScope bind(@PathVariable long id,@RequestBody Binding binding) {return vault.bind(id,binding.ref());}
    @DeleteMapping("/api/v1/admin/credentials/{ref}")
    public ResponseEntity<Void> revoke(@PathVariable String ref) {return vault.revoke(ref)?ResponseEntity.noContent().build():ResponseEntity.notFound().build();}
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<java.util.Map<String,String>> unavailable(Exception error) {return ResponseEntity.status(503).body(java.util.Map.of("code","CREDENTIAL_UNAVAILABLE","message","Credential storage unavailable"));}
}
