package com.kholodilin.repogrowth.credentials;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CredentialEncryptionTest {
    String key(int marker){byte[] bytes=new byte[32];Arrays.fill(bytes,(byte)marker);return Base64.getEncoder().encodeToString(bytes);}
    @Test void authenticatedEncryptionUsesRandomNoncesAndBindsRefVersionAndKeyId() {
        var properties=new CredentialVaultProperties(true,"key-a",Map.of("key-a",key(1)),null);var encryption=new CredentialEncryption(properties);
        var a=encryption.encrypt("github-main",1,"github-secret");var b=encryption.encrypt("github-main",1,"github-secret");
        assertThat(a.nonce()).isNotEqualTo(b.nonce());assertThat(encryption.decrypt("github-main",1,a)).isEqualTo("github-secret");
        assertThatThrownBy(()->encryption.decrypt("other",1,a)).isInstanceOf(IllegalStateException.class).hasNoCause();
        assertThatThrownBy(()->encryption.decrypt("github-main",2,a)).isInstanceOf(IllegalStateException.class);
        byte[] altered=a.ciphertext();altered[0]^=1;
        assertThatThrownBy(()->encryption.decrypt("github-main",1,new CredentialEncryption.Encrypted(a.keyId(),a.nonce(),altered))).isInstanceOf(IllegalStateException.class);
        assertThat(properties.toString()).doesNotContain(key(1));assertThat(a.toString()).doesNotContain("github-secret");
    }
    @Test void keyringRotationRetainsOldDecryptKeysAndFailsClosedIfKeyIsMissing() {
        var first=new CredentialEncryption(new CredentialVaultProperties(true,"old",Map.of("old",key(1)),null));var value=first.encrypt("ref",1,"token");
        var rotated=new CredentialEncryption(new CredentialVaultProperties(true,"new",Map.of("old",key(1),"new",key(2)),null));
        assertThat(rotated.decrypt("ref",1,value)).isEqualTo("token");assertThat(rotated.encrypt("ref",2,"token2").keyId()).isEqualTo("new");
        var missing=new CredentialEncryption(new CredentialVaultProperties(true,"new",Map.of("new",key(2)),null));
        assertThatThrownBy(()->missing.decrypt("ref",1,value)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("token");
    }
}
