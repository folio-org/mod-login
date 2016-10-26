/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.folio.auth.login_module;

import io.vertx.core.json.JsonObject;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.xml.bind.DatatypeConverter;

/**
 *
 * @author kurt
 */
public class AuthUtil {

  private static final String DEFAULT_ALGORITHM = "PBKDF2WithHmacSHA1";
  private static final int DEFAULT_ITERATIONS = 1000;
  private static final int DEFAULT_KEYLENGTH = 160;
  
  private String algorithm;
  private int iterations;
  private int keyLength;
  
  public AuthUtil(String algorithm, int iterations, int keyLength) {
    this.algorithm = algorithm;
    this.iterations = iterations;
    this.keyLength = keyLength;
  }
  
  public AuthUtil() {
    this.algorithm = DEFAULT_ALGORITHM;
    this.iterations = DEFAULT_ITERATIONS;
    this.keyLength = DEFAULT_KEYLENGTH;
  }
  
  public String calculateHash(String password, String salt) {
    //public String calculateHash(String password, String salt) {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), DatatypeConverter.parseHexBinary(salt), iterations, keyLength);
    byte[] hash;
    try {
      SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(algorithm);
      hash = keyFactory.generateSecret(spec).getEncoded();
    } catch(NoSuchAlgorithmException | InvalidKeySpecException e) {
      throw new RuntimeException(e);
    }
    return DatatypeConverter.printHexBinary(hash);
  }
    
  public String getSalt() {
    SecureRandom random = new SecureRandom();
    byte bytes[] = new byte[20];
    random.nextBytes(bytes);
    return DatatypeConverter.printHexBinary(bytes);
  }
}
