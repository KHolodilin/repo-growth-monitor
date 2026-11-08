package com.kholodilin.repogrowth.credentials;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
/** AES-256-GCM with per-write random nonce and row-bound authenticated metadata. */
public final class CredentialEncryption {
    public record Encrypted(String keyId,byte[] nonce,byte[] ciphertext) {
        public Encrypted {nonce=nonce.clone();ciphertext=ciphertext.clone();}
        @Override public byte[] nonce() {return nonce.clone();}
        @Override public byte[] ciphertext() {return ciphertext.clone();}
        @Override public String toString() {return "Encrypted[REDACTED]";}
    }
    private final Map<String,SecretKeySpec> keys=new HashMap<>();
    private final String activeKey;
    private final SecureRandom random=new SecureRandom();
    public CredentialEncryption(CredentialVaultProperties properties) {
        activeKey=properties.activeKeyId();
        properties.encryptionKeys().forEach((id,encoded)->{
            byte[] key;
            try {key=Base64.getDecoder().decode(encoded);} catch(IllegalArgumentException e) {throw new IllegalArgumentException("Invalid encryption key encoding");}
            if(!id.matches("[A-Za-z0-9_-]{1,64}") || key.length!=32) throw new IllegalArgumentException("AES-256 keys and valid key IDs are required");
            keys.put(id,new SecretKeySpec(key,"AES"));Arrays.fill(key,(byte)0);
        });
    }
    private byte[] aad(String ref,long version,String keyId) {return ("rgm-github:"+ref+":"+version+":"+keyId).getBytes(StandardCharsets.UTF_8);}
    public Encrypted encrypt(String ref,long version,String secret) {
        byte[] nonce=new byte[12];random.nextBytes(nonce);
        byte[] plain=secret.getBytes(StandardCharsets.UTF_8);
        try {var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,keys.get(activeKey),new GCMParameterSpec(128,nonce));cipher.updateAAD(aad(ref,version,activeKey));return new Encrypted(activeKey,nonce,cipher.doFinal(plain));}
        catch(GeneralSecurityException e) {throw new IllegalStateException("Credential encryption unavailable");}
        finally {Arrays.fill(plain,(byte)0);}
    }
    public String decrypt(String ref,long version,Encrypted value) {
        if(!keys.containsKey(value.keyId())) throw new IllegalStateException("Credential encryption key unavailable");
        byte[] plain=null;
        try {var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,keys.get(value.keyId()),new GCMParameterSpec(128,value.nonce()));cipher.updateAAD(aad(ref,version,value.keyId()));plain=cipher.doFinal(value.ciphertext());return new String(plain,StandardCharsets.UTF_8);}
        catch(GeneralSecurityException e) {throw new IllegalStateException("Credential decryption unavailable");}
        finally {if(plain!=null) Arrays.fill(plain,(byte)0);}
    }
}
