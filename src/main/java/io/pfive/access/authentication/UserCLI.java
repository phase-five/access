// Copyright 2023-2025 Phase Five LLC.  For license terms, see LICENSE.txt in the repository root.

package io.pfive.access.authentication;

import java.util.Base64;
import java.util.Scanner;

/// This class provides a main method that is used to add new users. It generates salt and hashes
/// a password for inclusion in the configuration file at config/users.csv. It does not currently
/// hide the typed password from the display, which is surprisingly messy to achieve. We may want
/// the creation and modification of user records to eventually occur entrely via API endpoints.
public class UserCLI {

    public static void main (String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter password: ");
        String password = scanner.nextLine();
        UserIdentity identity = new UserIdentity("none", "none", "none");
        UserHashRecord hashRecord = UserHashRecord.create(identity, password);
        System.out.println("Add a line to conf/users.csv with the following salt and hash:");
        System.out.println(String.join(",",
              new String(Base64.getEncoder().encode(hashRecord.salt)),
              new String(Base64.getEncoder().encode(hashRecord.hash))));
    }

}
