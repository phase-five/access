// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.authentication;

import io.pfive.access.store.JsonStore;
import io.pfive.access.util.Ret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.pfive.access.util.Ret.err;
import static io.pfive.access.util.Ret.ok;

/// Authentication component to be shared between the HTTP handlers for token creation and token interpretation.
/// This is used in two stages. First, a user authenticates (providing username and password) and receives a bearer
/// token in return. Then all subsequent requests include this token in a header or query parameter to indicate both
/// who the user is and that they've been through the authentication stage. Both must be performed over an encrypted
/// connection as they involve cleartext passwords and tokens. They are handled by two different Handler implementations
/// because one is a request-response cycle, while the other just extracts the token before passing control on to
/// another Handler. Because the Maps here are being not just read but written to by multiple threads (from multiple
/// HTTP requests) the methods or Maps must be synchronized. There are multiple Maps which may be reverse or index
/// views of one another. So if the individual collections are synchronized, rather than whole "transactions" in methods
/// modifying more than one of them, it is possible for to see an inconsistent state at certain very brief moments,
/// which is at worst expected to lead to an error message or require re-authentication. The lock-free reads and simple
/// maintenance and semantics of ConcurrentHashMap are considered more desirable as long as there are no security risks.
public class AuthMechanism {
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    static final String CSV_FIELDS[] = new String[] {"name", "email", "organization", "salt", "hash"};

    private final Map<String, UserHashRecord> hashForUserEmail = new ConcurrentHashMap<>();
    private final Map<Token, UserIdentity> userIdentityForToken = new ConcurrentHashMap<>();
    // This serves only to ensure that each user has only one token active at a time.
    // The email address serves as a unique identifier for individual users.
    private final Map<String, Token> tokenForEmail = new ConcurrentHashMap<>();

    public AuthMechanism () {
        loadUsersFromFile();
    }

    public Ret<UserIdentity> userIdentityForToken (Token token) {
        UserIdentity userIdentity = userIdentityForToken.get(token);
        if (userIdentity == null) {
            return err("No user is associated with the supplied token.");
        }
        return ok(userIdentity);
    }

    public Ret<Token> authenticateUser (String email, String password) {
        UserHashRecord userHashRecord = hashForUserEmail.get(email);
        if (userHashRecord == null) {
            return err("No user is associated with the supplied email.");
        }
        if (!userHashRecord.correctPassword(password)) {
            return err("Incorrect password.");
        }
        Token token = new Token();
        userIdentityForToken.put(token, userHashRecord.identity);
        Token previousToken = tokenForEmail.put(userHashRecord.identity.email, token);
        // A user may have only one token at a time. Remove any previously registered one.
        if (previousToken != null) {
            LOG.info("Purged old token {} for user {}.", previousToken.asString(), userHashRecord.identity.email);
            userIdentityForToken.remove(previousToken);
        }
        return ok(token);
    }

    protected void addHashRecord (UserHashRecord userHashRecord) {
        hashForUserEmail.put(userHashRecord.identity.email, userHashRecord);
    }

    /// Can be used to bootstrap the system in testing, to get at least one user into the maps.
    public void addUser (UserIdentity userIdentity, String password) {
        UserHashRecord userHashRecord = UserHashRecord.create(userIdentity, password);
        addHashRecord(userHashRecord);
    }

    private record UserCsvRow (String name, String email, String organization, String salt, String hash) {
        static UserCsvRow fromArray(String[] fields) {
            return new UserCsvRow(fields[0], fields[1], fields[2], fields[3], fields[4]);
        }
        UserHashRecord toUserHashRecord() {
            UserIdentity userIdentity = new UserIdentity(name, email, organization);
            byte[] bSalt = Base64.getDecoder().decode(salt);
            byte[] bHash = Base64.getDecoder().decode(hash);
            return new UserHashRecord(userIdentity, bSalt, bHash);
        }
    }

    private void loadUsersFromFile () {
        File csvFile = new File("./conf/users.csv").getAbsoluteFile();
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            int row = 0;
            for (String line = br.readLine(); line != null; line = br.readLine(), row++) {
                String[] fields = line.split(",");
                if (row == 0) {
                    if (!Arrays.equals(fields, CSV_FIELDS)) {
                        throw new RuntimeException("Incorrect headers on first row of users.csv.");
                    }
                    continue;
                }
                if (line.isBlank()) continue;
                if (fields.length != CSV_FIELDS.length) {
                    throw new RuntimeException("Users CSV file contains incorrect number of fields on row " + row);
                }
                UserHashRecord hashRecord = UserCsvRow.fromArray(fields).toUserHashRecord();
                addHashRecord(hashRecord);
                // FIXME this is not the best place to ensure storage directories exist
                JsonStore.createOrganizationDirectoryAsNeeded(hashRecord.identity);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
