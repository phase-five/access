# Phase Five Access

## Introduction

Phase Five Access is a system for transportation and land use analysis whose primary focus is on cumulative access to opportunities indicators for public transport and active modes. It creates transportation network models from open data in the GTFS and OSM formats, and aims to be usable and efficient at the geographic scale of medium-sized countries or whole states and provinces, even those with large areas of low density or disjoint/sparse network coverage. It has a browser-based interface which is intended to make it equally usable on the user's own machine or deployed on a remote server.

This is above all a platform for experimentation that should:

- Facilitate research and development on rapid-turnaround implementations of ideas borrowed from traditional traffic and land use models;
- Provide a testbed for new optimizations and approaches in cumulative access to opportunities computation;
- Ensure the academic, scientific, and public policy communities have access to fast and simple cumulative opportunities metrics;
- Serve as a proof of concept for radical simplifications to in-browser interfaces and CPU-bound API servers.

Please be aware that this tool is under active development and prioritizes innovation and flexibility. Using it will require a significant amount of manual configuration and deployment effort. Results may vary from one version to the next, and may be insufficiently validated or even incorrect in some versions. However, our hope is that it will eventually become a viable complement or alternative to R5 wrapper libraries (such as [R5R](https://ipeagit.github.io/r5r/) and [R5Py](https://r5py.readthedocs.io/stable/)) now in widespread use by the research and data science community.

Conveyal LLC provides a managed SaaS tool that is also based on R5 but significantly more stable and heavily tested. Conveyal provides hosting, support, and collaborative scenario design tools for teams. [Please contact Conveyal](https://conveyal.com) if you are seeking a proven product with a more seamless user experience.

### NOTICE

This is a research and development project investigating new methods for the computation and visualization of cumulative access to opportunities indicators at large geographic scales (e.g. entire large European countries or US states). Thus far, the focus of this work has been on algorithmic optimizations rather than stability or output consistency. As a research prototype, this system has no safeguards against incorrect usage or extreme resource consumption. Its output has not yet been thoroughly validated and should not be considered reliable. Although it is designed as a multi-user system it has never been subject to sustained use by multiple clients, and robust operation under such conditions is not currently the highest priority. Please do not rely on this software in its current state for any serious decision making or scientific inquiry.

## License and Copyright

This software is published under a source-available proprietary license. While it does not meet the customary definition of "open source", the software may be used and modified for most **noncommercial** purposes. See [the license notice](LICENSE.txt) for details.

The following noncommercial uses are explicitly permitted and encouraged:
- Use by teachers, researchers, and students in the context of academic, teaching, or scientific
  activity in a school, university, or public research institution;
- Use by civil servants and other direct employees of public institutions in the course of their
  internal work for those organizations;
- Use by private individuals for learning, experimentation, and advocacy.

If in doubt or if you have a specific commercial use in mind, feel free to contact us by email at `contact@phasefive.co` and inquire about licensing for your particular use case.

This software and its source code are made available primarily in the interest of methodological transparency and to encourage and facilitate adoption of sound methods in the scientific and public policy domains. Suggestions and bug reports are welcome, but for the time being this is primarily an internal project rather than a community project built around outside contributions.

Development began in 2023 at the initiative of [Phase Five LLC](http://phasefive.co). In 2025 it has been supported by the _Direction générale des infrastructures, des transports et des mobilités_ ([DGITM](https://www.ecologie.gouv.fr/direction-generale-des-infrastructures-des-transports-et-des-mobilites-dgitm) - the French Department of Transportation, Infrastructure, and Mobility) via the cooperative [Codeur·euses en Liberté](https://www.codeureusesenliberte.fr). Copyright on all source code and documentation is held by Phase Five LLC.

## Building the Java Backend

This software is not currently provided as pre-built binaries or container images. To build and launch it you will need a Java 24 Development Kit (JDK) and the Maven build tool, as well as the Gradle build tool to build a dependency. Installing these tools is beyond the scope of this document, but you can for example install JDK 24 on a Debian-derived x86 Linux system with:
```shell
wget https://download.oracle.com/java/24/latest/jdk-24_linux-x64_bin.deb
sudo apt install ./jdk-24_linux-x64_bin.deb 
```

This software currently relies on [Conveyal R5](https://github.com/conveyal/r5) to perform routing calculations. No R5 artifact is available on Maven Central, so you will need to check out v7.4 of R5 yourself, then build and publish it to your local Maven repository with a command like `gradle publishToMavenLocal`. This is known to work with Gradle version 8.14.

Once you have built an R5 artifact and made it available locally, you can switch to your local clone of the present repository and build it with `mvn clean package`. If the build successfully completes, you can move on to some basic configuration.

## Configuration

### TLS Certificates

All communication between the UI and the backend is over HTTPS (TLS). You will therefore need to generate a certificate and private key for the server, which should be saved in `conf/tls.crt` and `conf/tls.key` under the root of the repo in files readable by the user which will run the server. We do not use the traditional Java keystore, these are just standard PEM files containing an X.509 certificate and private key.

For a public server these could be bought from a certificate authority or generated with a service like Let's Encrypt. For local usage, testing, and development the simplest approach is to generate a self-signed certificate:
```shell
sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 -keyout tls.key -out tls.crt
```
You can just hit enter and use the default values for all fields except the "common name". The server and client will
check the domain name in the URL against the common name in the certificate, which should be set to `localhost` or the
IP address you will use in the URL when connecting to the server. Though some clients (browsers) can be told to tolerate
certificates lacking a common name, the Jetty server used in Phase Five Access will attempt to apply
[Server Name Indication (SNI)](https://en.wikipedia.org/wiki/Server_Name_Indication) and fail.

Some browsers will refuse to consider the site safe if the certificate doesn't have a common name, even if you tell them
to trust the certificate. In Chrome (and probably other browsers) this will disable the browser cache, making behavior
significantly different than when deployed normally, as even with user approval to load an unsafe site its contents are
never cached.

A more thorough solution is to set yourself up as a certificate authority (CA), trust your own CA root
certificate, and then issue yourself a certificate for the common name such as `localhost`. See https://stackoverflow.com/a/60516812 for instructions.

### User Accounts and Passwords

User accounts are configured via the file `conf/users.csv` which contains one line per user, and must begin with the header row `name,email,organization,salt,hash`. By default, the file contains one line for user `test@test.org` with password `secret`. To generate salt and hash values for a new user, run the UserCLI utility with a command like that in `user.sh` in the `run` directory. Note that while this authentication mechanism generally follows best practices, it is largely a proof of concept and is has not yet been audited. This system should not be considered secure or used to transmit or store sensitive or secret information.

### Client Dependencies

The web UI uses only one piece of external software. This is [Mapbox GL JS](https://docs.mapbox.com/mapbox-gl-js/guides), which it fetches from a CDN using a Javascript import statement. Its license requires you to have a Mapbox account to use it. You will need to supply an API key from your Mapbox account as the `MAPBOX_ACCESS_TOKEN` constant at the top of `static/private/util/map-common.js`.

### Firewall Settings

The backend will listen for TCP connections on ports 4343 (HTTPS/TLS) and 8080 (HTTP). The non-TLS HTTP server does nothing but redirect to HTTPS. For a single-node setup you will need to open at least 4343 or perhaps both 4343 and 8080.

## Launching

The main class for the backend is `io.pfive.access.Main` which is defined in the JAR manifest via the Maven project model (POM). This means that Maven can automatically start the backend with all the right dependency JARs with am `exec:java` command like the one in `webui.sh` in the `run` directory. This script allows the JVM to consume a large amount of heap memory and will run in the background with nohup, which is typical of a server environment. If needed, you may set a different (smaller) maximum heap size and the `mvn` command may be changed to run in the foreground without nohup.

## Concept and Design

This project consists of a Java HTTP API server and an accompanying ECMAScript frontend. It began as a prototype or proof of concept for a more direct and low-level approach to web application development, using less libraries, frameworks, tooling and abstractions than has been prevalent in recent years. 

Many dominant approaches in industry were born out of the needs of large corporations and fast-moving VC-funded startups dealing with the overhead of communication and control across teams. They face widely varying skill levels and regular turnover in software developer positions, while anticipating rapid scaling to millions or hundreds of millions of mass-market users.

These needs differ strongly from those of a small research team or consultancy, where a limited number of highly experienced specialists are expected to deeply understand all aspects of a system and rapidly iterate on changes that cut across functional and structural boundaries. Such systems may be used by tens or hundreds of people at most, but with high expectations on performance or methodological rigor.

Some design choices in this project are more extreme than they would strictly need to be, in the interest of testing and demonstrating an alternative approach.

The Java backend is coded directly against the Jetty HTTP server with no framework layered on top. It provides simple token-based authentication over TLS (using plain certificate files rather than the Java keystore), some background task handling and progress reporting, and storage and retrieval of files and metadata. A simple TCP remote procedure call mechanism is provided for distributing batches of tasks across multiple compute nodes with backpressure.

The frontend is coded "on the platform" of ECMAScript, HTML custom elements, and standard web APIs, with absolutely no build tools, transpilers, or package tools and only one dependency (retrieved at runtime via CDN). It is served as plain static files by the same Jetty server providing the backend API, facilitating debugging and avoiding complexity from mechanisms like CORS. It is a multi-page app that reuses custom elements to maintain visual and functional consistency across pages.

File formats and interfaces have been chosen to provide the most interoperability and functionality with the least code paths and dependencies on external services, in hopes that maintenance and experimentation will be manageable and pleasant even for small teams.

Coding style generally aims to be direct and procedural, facilitating understanding and modification by people unfamiliar with frontend frameworks as well as developers coming from other engineering and scientific computing domains.

An alternative backend is under development in the Rust language, but it remains quite experimental and has not yet been published.

## Further Usage Details

### Startup

Run with `mvn exec:java`. The exec plugin does not ensure the build is up to date. 
To ensure the build is up to date, you can use the command `mvn package exec:java`.

`mvn exec` will run the application inside the same JVM as Maven itself. So to increase the memory available to this application, you need to set the JVM options for Maven. There are [several ways to do this](https://maven.apache.org/configure.html) but a straightforward one is the `MAVEN_OPTS` environment variable. Either export it or assign to it in the same command you use to lauch Maven:

```bash
export MAVEN_OPTS=-Xmx120G
mvn exec:java
# or simply:
MAVEN_OPTS=-Xmx120G mvn exec:java
```
Another option may be using `mvn exec:exec` to fork another JVM.
To verify that Maven is running with your intended memory settings, just check `ps aux | grep java`.

### API Authentication

First get a token with a basic authorization request to `/token`:
```
curl --insecure -v "https://localhost:4343/token" -H "authorization: Basic test@test.org:secret"
...
> GET /token HTTP/1.1
> authorization: Basic test@test.org:secret
>
< HTTP/1.1 200 OK
< Content-Type: text/plain;charset=utf-8
< Content-Length: 32
<
* Connection #0 to host localhost left intact
SaZlN3GREhSYFk7BhL-Ohp_K-QFKaXRf%
* 
```
The server response is 32 characters of URL-safe base64. This is then passed to any other requests in a Bearer authorization header:
```
curl --insecure -v "https://localhost:4343/static/" -H "Authorization: Bearer SaZlN3GREhSYFk7BhL-Ohp_K-QFKaXRf"
```


