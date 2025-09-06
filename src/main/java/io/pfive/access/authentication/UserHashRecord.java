// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.authentication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.lang.invoke.MethodHandles;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.random.RandomGenerator;

// package private
class UserHashRecord {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private static final RandomGenerator randomGenerator = new SecureRandom();
    private static final int SALT_LENGTH_BYTES = 32;
    private static final int HASH_ITERATIONS = 1024 * 512; // 512k iterations takes about 200 msec on recent Macbook.
    private static final int HASH_LENGTH_BITS = 256;

    final UserIdentity identity;
    final byte[] salt;
    final byte[] hash;

    protected UserHashRecord (UserIdentity identity, byte[] salt, byte[] hash) {
        this.identity = identity;
        this.salt = salt;
        this.hash = hash;
    }

    // Restrict password to printable 7-bit ASCII

    public static UserHashRecord create (UserIdentity identity, String password) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        randomGenerator.nextBytes(salt);
        byte[] hash = computeHash(salt, password);
        return new UserHashRecord(identity, salt, hash);
    }

    /// Uses PBKDF2 key stretching to slow down hash rate. This method is included in the JDK.
    /// It uses very little memory so is not safe against purpose-built cracking hardware.
    /// BCrypt and SCrypt are also good options that use more memory, but are not included in the JDK.
    private static byte[] computeHash (byte[] salt, String password) {
        // The actual SecretKeyFactory implementing classes like PBKDF2Core.HmacSHA512 are private.
        // Java SPI requires us to look them up using Strings, throwing checked exceptions that can
        // never happen. SecretKeyFactory instances appear to be non-threadsafe.
        // Fetch one per operation until proven otherwise.
        try {
            long startTime = System.currentTimeMillis();
            SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance( "PBKDF2WithHmacSHA512" );
            // Java char is 2-byte UTF16. The key spec appears to require passwords in this encoding.
            KeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, HASH_ITERATIONS, HASH_LENGTH_BITS);
            SecretKey key = secretKeyFactory.generateSecret(keySpec);
            long elapsedTime = System.currentTimeMillis() - startTime;
            LOG.info("Time to hash (msec): " + elapsedTime);
            return key.getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Error while hashing.", e);
        }
    }

    public boolean correctPassword (String password) {
        return Arrays.equals(computeHash(salt, password), this.hash);
    }

}
