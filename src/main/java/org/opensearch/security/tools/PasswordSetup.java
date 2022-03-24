/*
 * Portions Copyright OpenSearch Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.opensearch.security.tools;

import com.fasterxml.jackson.databind.InjectableValues;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Iterators;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.opensearch.action.admin.cluster.health.ClusterHealthRequest;
import org.opensearch.action.admin.cluster.health.ClusterHealthResponse;
import org.opensearch.client.Request;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.security.DefaultObjectMapper;
import org.opensearch.security.NonValidatingObjectMapper;
import org.opensearch.security.ssl.util.ExceptionUtils;
import org.opensearch.security.support.ConfigConstants;
import org.opensearch.security.support.PemKeyReader;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;

public class PasswordSetup {

    private static final String OPENDISTRO_SECURITY_TS_PASS = "OPENDISTRO_SECURITY_TS_PASS";
    private static final String OPENDISTRO_SECURITY_KS_PASS = "OPENDISTRO_SECURITY_KS_PASS";
    private static final String OPENDISTRO_SECURITY_KEYPASS = "OPENDISTRO_SECURITY_KEYPASS";
    public static void main(final String[] args) {
        try{
            final int returnCode = execute(args);
            System.exit(returnCode);
        } catch (Throwable e) {
            System.out.println("Unexpected error");
            System.exit(-1);
        }
    }

    public static int execute(final String[] args) throws Exception {

        System.setProperty("security.nowarn.client","true");
        System.setProperty("jdk.tls.rejectClientInitiatedRenegotiation","true");

        final HelpFormatter formatter = new HelpFormatter();
        Options options = new Options();
        options.addOption( "nhnv", "disable-host-name-verification", false, "Disable hostname verification" );
        options.addOption(Option.builder("ts").longOpt("truststore").hasArg().argName("file").desc("Path to truststore (JKS/PKCS12 format)").build());
        options.addOption(Option.builder("ks").longOpt("keystore").hasArg().argName("file").desc("Path to keystore (JKS/PKCS12 format").build());
        options.addOption(Option.builder("tst").longOpt("truststore-type").hasArg().argName("type").desc("JKS or PKCS12, if not given we use the file extension to dectect the type").build());
        options.addOption(Option.builder("kst").longOpt("keystore-type").hasArg().argName("type").desc("JKS or PKCS12, if not given we use the file extension to dectect the type").build());
        options.addOption(Option.builder("tspass").longOpt("truststore-password").hasArg().argName("password").desc("Truststore password").build());
        options.addOption(Option.builder("kspass").longOpt("keystore-password").hasArg().argName("password").desc("Keystore password").build());
        options.addOption(Option.builder("cd").longOpt("configdir").hasArg().argName("directory").desc("Directory for config files").build());
        options.addOption(Option.builder("h").longOpt("hostname").hasArg().argName("host").desc("OpenSearch host (default: localhost)").build());
        options.addOption(Option.builder("p").longOpt("port").hasArg().argName("port").desc("OpenSearch transport port (default: 9200)").build());
        options.addOption(Option.builder("cn").longOpt("clustername").hasArg().argName("clustername").desc("Clustername (do not use together with -icl)").build());
        options.addOption( "sniff", "enable-sniffing", false, "Enable client.transport.sniff" );
        options.addOption( "icl", "ignore-clustername", false, "Ignore clustername (do not use together with -cn)" );
        options.addOption(Option.builder("r").longOpt("retrieve").desc("retrieve current config").build());
        options.addOption(Option.builder("f").longOpt("file").hasArg().argName("file").desc("file").build());
        options.addOption(Option.builder("t").longOpt("type").hasArg().argName("file-type").desc("file-type").build());
        options.addOption(Option.builder("ksalias").longOpt("keystore-alias").hasArg().argName("alias").desc("Keystore alias").build());
        options.addOption(Option.builder("ec").longOpt("enabled-ciphers").hasArg().argName("cipers").desc("Comma separated list of enabled TLS ciphers").build());
        options.addOption(Option.builder("ep").longOpt("enabled-protocols").hasArg().argName("protocols").desc("Comma separated list of enabled TLS protocols").build());
        //TODO mark as deprecated and replace it with "era" if "era" is mature enough
        options.addOption(Option.builder("us").longOpt("update_settings").hasArg().argName("number of replicas").desc("Update the number of Security index replicas, reload configuration on all nodes and exit").build());
        options.addOption(Option.builder("i").longOpt("index").hasArg().argName("indexname").desc("The index OpenSearch Security uses to store the configuration").build());
        options.addOption(Option.builder("era").longOpt("enable-replica-autoexpand").desc("Enable replica auto expand and exit").build());
        options.addOption(Option.builder("dra").longOpt("disable-replica-autoexpand").desc("Disable replica auto expand and exit").build());
        options.addOption(Option.builder("rl").longOpt("reload").desc("Reload the configuration on all nodes, flush all Security caches and exit").build());
        options.addOption(Option.builder("ff").longOpt("fail-fast").desc("fail-fast if something goes wrong").build());
        options.addOption(Option.builder("dg").longOpt("diagnose").desc("Log diagnostic trace into a file").build());
        options.addOption(Option.builder("dci").longOpt("delete-config-index").desc("Delete '.opendistro_security' config index and exit.").build());
        options.addOption(Option.builder("esa").longOpt("enable-shard-allocation").desc("Enable all shard allocation and exit.").build());
        options.addOption(Option.builder("arc").longOpt("accept-red-cluster").desc("Also operate on a red cluster. If not specified the cluster state has to be at least yellow.").build());

        options.addOption(Option.builder("cacert").hasArg().argName("file").desc("Path to trusted cacert (PEM format)").build());
        options.addOption(Option.builder("cert").hasArg().argName("file").desc("Path to admin certificate in PEM format").build());
        options.addOption(Option.builder("key").hasArg().argName("file").desc("Path to the key of admin certificate").build());
        options.addOption(Option.builder("keypass").hasArg().argName("password").desc("Password of the key of admin certificate (optional)").build());

        options.addOption(Option.builder("si").longOpt("show-info").desc("Show system and license info").build());

        options.addOption(Option.builder("w").longOpt("whoami").desc("Show information about the used admin certificate").build());

        options.addOption(Option.builder("prompt").longOpt("prompt-for-password").desc("Prompt for password if not supplied").build());

        options.addOption(Option.builder("er").longOpt("explicit-replicas").hasArg().argName("number of replicas").desc("Set explicit number of replicas or autoexpand expression for .opendistro_security index").build());

        options.addOption(Option.builder("backup").hasArg().argName("folder").desc("Backup configuration to folder").build());

        options.addOption(Option.builder("migrate").hasArg().argName("folder").desc("Migrate and use folder to store migrated files").build());
        
        options.addOption(Option.builder("rev").longOpt("resolve-env-vars").desc("Resolve/Substitute env vars in config with their value before uploading").build());

        options.addOption(Option.builder("vc").numberOfArgs(1).optionalArg(true).argName("version").longOpt("validate-configs").desc("Validate config for version 6 or 7 (default 7)").build());

        options.addOption(Option.builder("mo").longOpt("migrate-offline").hasArg().argName("folder").desc("Migrate and use folder to store migrated files").build());
        
        String hostname = "localhost";
        int port = 9200;
        String kspass = System.getenv(OPENDISTRO_SECURITY_KS_PASS);
        String tspass = System.getenv(OPENDISTRO_SECURITY_TS_PASS);
        String cd = ".";
        String ks = null;
        String ts = null;
        String kst = null;
        String tst = null;
        boolean nhnv = false;

        String clustername = "opensearch";
        String file = null;
        String type = null;
        String ksAlias = null;
        String[] enabledProtocols = new String[0];
        String[] enabledCiphers = new String[0];
        String index = ConfigConstants.OPENDISTRO_SECURITY_DEFAULT_CONFIG_INDEX;
        boolean failFast = false;
        boolean acceptRedCluster = false;
        
        String keypass = System.getenv(OPENDISTRO_SECURITY_KEYPASS);
        String cacert = null;
        String cert = null;
        String key = null;
        boolean si;
        boolean whoami;
        final boolean promptForPassword;
        String explicitReplicas = null;
        Integer validateConfig = null;
        String migrateOffline = null;

        InjectableValues.Std injectableValues = new InjectableValues.Std();
        injectableValues.addValue(Settings.class, Settings.builder().build());
        DefaultObjectMapper.inject(injectableValues);
        NonValidatingObjectMapper.inject(injectableValues);

        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine line = parser.parse( options, args );
            
            validate(line);
            
            hostname = line.getOptionValue("h", hostname);
            port = Integer.parseInt(line.getOptionValue("p", String.valueOf(port)));

            promptForPassword = line.hasOption("prompt");
            
            if(kspass == null || kspass.isEmpty()) {
                kspass = line.getOptionValue("kspass",promptForPassword?null:"changeit");
            }
            
            if(tspass == null || tspass.isEmpty()) {
                tspass = line.getOptionValue("tspass",promptForPassword?null:kspass);
            }

            cd = line.getOptionValue("cd", cd);
            
            if(!cd.endsWith(File.separator)) {
                cd += File.separator;
            }
            
            ks = line.getOptionValue("ks",ks);
            ts = line.getOptionValue("ts",ts);
            kst = line.getOptionValue("kst", kst);
            tst = line.getOptionValue("tst", tst);
            nhnv = line.hasOption("nhnv");
            clustername = line.getOptionValue("cn", clustername);
            file = line.getOptionValue("f", file);
            type = line.getOptionValue("t", type);
            ksAlias = line.getOptionValue("ksalias", ksAlias);
            index = line.getOptionValue("i", index);
            
            String enabledCiphersString = line.getOptionValue("ec", null);
            String enabledProtocolsString = line.getOptionValue("ep", null);
            
            if(enabledCiphersString != null) {
                enabledCiphers = enabledCiphersString.split(",");
            }
            
            if(enabledProtocolsString != null) {
                enabledProtocols = enabledProtocolsString.split(",");
            }
            
            failFast = line.hasOption("ff");
            acceptRedCluster = line.hasOption("arc");
            
            cacert = line.getOptionValue("cacert");
            cert = line.getOptionValue("cert");
            key = line.getOptionValue("key");
            keypass = line.getOptionValue("keypass", keypass);
            si = line.hasOption("si");
            whoami = line.hasOption("w");
            explicitReplicas = line.getOptionValue("er", explicitReplicas);

            validateConfig = !line.hasOption("vc")?null:Integer.parseInt(line.getOptionValue("vc", "7"));
            
            if(validateConfig != null && validateConfig.intValue() != 6 && validateConfig.intValue() != 7) {
                throw new ParseException("version must be 6 or 7");
            }
            
            migrateOffline = line.getOptionValue("mo");
            
        }
        catch( ParseException exp ) {
            System.out.println("ERR: Parsing failed.  Reason: " + exp.getMessage());
            formatter.printHelp("set_passwords.sh", options, true);
            return -1;
        }
        
        if(migrateOffline != null) {
            System.out.println("Migrate "+migrateOffline+" offline");
            final boolean retVal =  Migrater.migrateDirectory(new File(migrateOffline), true);
            return retVal?0:-1;
        }

        System.out.print("Will connect to "+hostname+":"+port);
        Socket socket = new Socket();

        try {
            
            socket.connect(new InetSocketAddress(hostname, port));
            
          } catch (java.net.ConnectException ex) {
            System.out.println();
            System.out.println("ERR: Seems there is no OpenSearch running on "+hostname+":"+port+" - Will exit");
            return (-1);
          } finally {
              try {
                socket.close();
            } catch (Exception e) {
                //ignore
            }
          }
        System.out.println(" ... done");

        if(ks != null) {
            kst = kst==null?(ks.endsWith(".jks")?"JKS":"PKCS12"):kst;
            if(kspass == null && promptForPassword) {
                kspass = promptForPassword("Keystore", "kspass", OPENDISTRO_SECURITY_KS_PASS);
            }
        }
        
        if(ts != null) {
            tst = tst==null?(ts.endsWith(".jks")?"JKS":"PKCS12"):tst;
            if(tspass == null && promptForPassword) {
                tspass = promptForPassword("Truststore", "tspass", OPENDISTRO_SECURITY_TS_PASS);
            }
        }            

        if(key != null) {

            if(keypass == null && promptForPassword) {
                keypass = promptForPassword("Pemkey", "keypass", OPENDISTRO_SECURITY_KEYPASS);
            }

        }
        
        final SSLContext sslContext = sslContext(ts, tspass, tst, ks, kspass, kst, ksAlias, cacert, cert, key, keypass);

        try (RestHighLevelClient restHighLevelClient = getRestHighLevelClient(sslContext, nhnv, enabledProtocols, enabledCiphers, hostname, port)) {
            RestClient lowLevelClient = restHighLevelClient.getLowLevelClient();
		    Response whoAmIRes = lowLevelClient.performRequest(new Request("GET", "/_plugins/_security/whoami"));
			if (whoAmIRes.getStatusLine().getStatusCode() != 200) {
				System.out.println("Unable to check whether cluster is sane because return code was " + whoAmIRes.getStatusLine());
				return (-1);
			}

			JsonNode whoAmIResNode = DefaultObjectMapper.objectMapper.readTree(whoAmIRes.getEntity().getContent());
			System.out.println("Connected as " + whoAmIResNode.get("dn"));

			if (!whoAmIResNode.get("is_admin").asBoolean()) {

				System.out.println("ERR: " + whoAmIResNode.get("dn") + " is not an admin user");

				if (!whoAmIResNode.get("is_node_certificate_request").asBoolean()) {
                    System.out.println("Seems you use a client certificate but this one is not registered as admin_dn");
                    System.out.println("Make sure opensearch.yml on all nodes contains:");
                    System.out.println("plugins.security.authcz.admin_dn:"+System.lineSeparator()+
                            "  - \"" + whoAmIResNode.get("dn") + "\"");
                } else {
                	System.out.println("Seems you use a node certificate. This is not permitted, you have to use a client certificate and register it as admin_dn in opensearch.yml");
                }
                return (-1);
            } else if (whoAmIResNode.get("is_node_certificate_request").asBoolean()) {
                System.out.println("ERR: Seems you use a node certificate which is also an admin certificate");
                System.out.println("     That may have worked with older OpenSearch Security versions but it indicates");
                System.out.println("     a configuration error and is therefore forbidden now.");
                if (failFast) {
                    return (-1);
                }

            }

            try {
                if(issueWarnings(restHighLevelClient) != 0) {
                    return (-1);
                }
            } catch (Exception e1) {
                System.out.println("Unable to check whether cluster is sane");
                throw e1;
            }

            if(si) {
                return (0);
            }

			if (whoami) {
				System.out.println(whoAmIResNode.toPrettyString());
                return (0);
            }

            if(failFast) {
                System.out.println("Fail-fast is activated");
            }

            System.out.println("Contacting opensearch cluster '"+clustername+"'"+(acceptRedCluster?"":" and wait for YELLOW clusterstate")+" ...");

			ClusterHealthResponse chResponse = null;

			while (chResponse == null) {
                try {
					final ClusterHealthRequest chRequest = new ClusterHealthRequest().timeout(TimeValue.timeValueMinutes(5));
					if (!acceptRedCluster) {
						chRequest.waitForYellowStatus();
					}
					chResponse = restHighLevelClient.cluster().health(chRequest, RequestOptions.DEFAULT);
				} catch (Exception e) {

                    Throwable rootCause = ExceptionUtils.getRootCause(e);
                    
                    if(!failFast) {
                        System.out.println("Cannot retrieve cluster state due to: "+e.getMessage()+". This is not an error, will keep on trying ...");
                        System.out.println("  Root cause: "+rootCause+" ("+e.getClass().getName()+"/"+rootCause.getClass().getName()+")");
                        System.out.println("   * Try running set_passwords.sh with -icl (but no -cl) and -nhnv (If that works you need to check your clustername as well as hostnames in your TLS certificates)");   
                        System.out.println("   * Make sure that your keystore or PEM certificate is a client certificate (not a node certificate) and configured properly in opensearch.yml");
                        System.out.println("   * If this is not working, try running set_passwords.sh with --diagnose and see diagnose trace log file)");
                        System.out.println("   * Add --accept-red-cluster to allow set_passwords to operate on a red cluster.");

                    } else {
                        System.out.println("ERR: Cannot retrieve cluster state due to: "+e.getMessage()+".");
                        System.out.println("  Root cause: "+rootCause+" ("+e.getClass().getName()+"/"+rootCause.getClass().getName()+")");
                        System.out.println("   * Try running set_passwords.sh with -icl (but no -cl) and -nhnv (If that works you need to check your clustername as well as hostnames in your TLS certificates)");
                        System.out.println("   * Make also sure that your keystore or PEM certificate is a client certificate (not a node certificate) and configured properly in opensearch.yml");
                        System.out.println("   * If this is not working, try running set_passwords.sh with --diagnose and see diagnose trace log file)"); 
                        System.out.println("   * Add --accept-red-cluster to allow set_passwords to operate on a red cluster.");

                        return (-1);
                    }
                    
                    Thread.sleep(3000);
                    continue;
                }
            }

            final boolean timedOut = chResponse.isTimedOut();

            if (!acceptRedCluster && timedOut) {
                System.out.println("ERR: Timed out while waiting for a green or yellow cluster state.");
                System.out.println("   * Try running set_passwords.sh with -icl (but no -cl) and -nhnv (If that works you need to check your clustername as well as hostnames in your TLS certificates)");
                System.out.println("   * Make also sure that your keystore or PEM certificate is a client certificate (not a node certificate) and configured properly in opensearch.yml");
                System.out.println("   * If this is not working, try running set_passwords.sh with --diagnose and see diagnose trace log file)"); 
                System.out.println("   * Add --accept-red-cluster to allow set_passwords to operate on a red cluster.");
                return (-1);
            }

            System.out.println("Clustername: " + chResponse.getClusterName());
			System.out.println("Clusterstate: " + chResponse.getStatus());
			System.out.println("Number of nodes: " + chResponse.getNumberOfNodes());
			System.out.println("Number of data nodes: " + chResponse.getNumberOfDataNodes());

            Scanner sc = new Scanner(System.in);
            ArrayList<String> users = new ArrayList<String>(Arrays.asList("admin", "kibanaserver", "kibanaro", "logstash", "readall", "snapshotrestore"));

            System.out.println("\n\nBeginning Password Setup");

            for (String user: users) {
                boolean isWeakPassword = true;
                while(isWeakPassword) {
                    try {
                        System.out.println("\nEnter password for " + user + ": ");
                        String password = sc.nextLine();
                        setPasswordSingleUser(user, lowLevelClient, password);
                        isWeakPassword = false;
                    } 
                    catch(ResponseException e) {
                        System.out.println(Settings.builder().get(ConfigConstants.SECURITY_RESTAPI_PASSWORD_VALIDATION_ERROR_MESSAGE));
                        System.out.print("Would you like to try again? [y/N] ");
                        if (!sc.nextLine().equals("y")) {
                            return -1;
                        }
                    }
                }
            }
            sc.close();
        } catch (Throwable e) {
            System.out.println(e);
            return -1;
        }
        return 0;
    }

    private static void setPasswordSingleUser(String user, RestClient restClient, String password) throws Exception {
        String body = "[{\"op\": \"add\", \"path\": \"/password\",\"value\": \"" + password + "\"}]";
        StringEntity entity = new StringEntity(body, ContentType.APPLICATION_JSON);
        Request request = new Request("PATCH", "/_plugins/_security/api/internalusers/" + user);
        request.setEntity(entity);
        restClient.performRequest(request);
    }

    private static String promptForPassword(String passwordName, String commandLineOption, String envVarName) throws Exception {
        final Console console = System.console();
        if(console == null) {
            throw new Exception("Cannot allocate a console. Set env var "+envVarName+" or "+commandLineOption+" on commandline in that case");
        }
        return new String(console.readPassword("[%s]", passwordName+" password:"));
    }

    private static SSLContext sslContext(
	        //keystore & trusstore related properties
			String ts,
			String tspass,
            String trustStoreType,
            String ks,
            String kspass,
            String keyStoreType,
            String ksAlias,

			//certs related properties
			String cacert,
			String cert,
			String key,
			String keypass) throws Exception {

        final SSLContextBuilder sslContextBuilder = SSLContexts.custom();

		if (ks != null) {
			File keyStoreFile = Paths.get(ks).toFile();

            KeyStore keyStore = KeyStore.getInstance(keyStoreType.toUpperCase());
			keyStore.load(new FileInputStream(keyStoreFile), kspass.toCharArray());
            sslContextBuilder.loadKeyMaterial(keyStore, kspass.toCharArray(), (aliases, socket) -> {
                if (aliases == null || aliases.isEmpty()) {
                    return ksAlias;
                }

                if (ksAlias == null || ksAlias.isEmpty()) {
                    return aliases.keySet().iterator().next();
                }

                return ksAlias;
            });
		}

		if (ts != null) {
			File trustStoreFile = Paths.get(ts).toFile();

			KeyStore trustStore = KeyStore.getInstance(trustStoreType.toUpperCase());
			trustStore.load(new FileInputStream(trustStoreFile), tspass == null ? null : tspass.toCharArray());
            sslContextBuilder.loadTrustMaterial(trustStore, null);
		}

		if (cacert != null) {
			File caCertFile = Paths.get(cacert).toFile();
			try (FileInputStream in = new FileInputStream(caCertFile)) {
				X509Certificate[] certificates = PemKeyReader.loadCertificatesFromStream(in);
				KeyStore trustStore = PemKeyReader.toTruststore("al", certificates);
                sslContextBuilder.loadTrustMaterial(trustStore, null);
			} catch (FileNotFoundException e) {
				throw new IllegalArgumentException("Could not find certificate file " + caCertFile, e);
			} catch (IOException | CertificateException e) {
				throw new IllegalArgumentException("Error while reading certificate file " + caCertFile, e);
			}
		}

        if (cert != null && key != null) {
			File certFile = Paths.get(cert).toFile();
			X509Certificate[] certificates;
			PrivateKey privateKey;
			try (FileInputStream in = new FileInputStream(certFile)) {
				certificates = PemKeyReader.loadCertificatesFromStream(in);
			} catch (FileNotFoundException e) {
				throw new IllegalArgumentException("Could not find certificate file " + certFile, e);
			} catch (IOException | CertificateException e) {
				throw new IllegalArgumentException("Error while reading certificate file " + certFile, e);
			}

			File keyFile = Paths.get(key).toFile();
			try (FileInputStream in = new FileInputStream(keyFile)) {
				privateKey = PemKeyReader.toPrivateKey(in, keypass);
			} catch (FileNotFoundException e) {
				throw new IllegalArgumentException("Could not find certificate key file " + keyFile, e);
			} catch (IOException e) {
				throw new IllegalArgumentException("Error while reading certificate key file " + keyFile, e);
			}

			String alias = "al";
			KeyStore keyStore = PemKeyReader.toKeystore(alias, "changeit".toCharArray(), certificates, privateKey);
            sslContextBuilder.loadKeyMaterial(keyStore, "changeit".toCharArray(), (aliases, socket) -> alias);
		}

		return sslContextBuilder.build();
	}

    private static int issueWarnings(RestHighLevelClient restHighLevelClient) throws IOException {
		Response res = restHighLevelClient.getLowLevelClient().performRequest(new Request("GET", "/_nodes"));

		if (res.getStatusLine().getStatusCode() != 200) {
			System.out.println("Unable to get nodes " + res.getStatusLine());
			return -1;
		}

		JsonNode resNode = DefaultObjectMapper.objectMapper.readTree(res.getEntity().getContent());

		int nodeCount = Iterators.size(resNode.at("/nodes").iterator());

		if (nodeCount > 0) {

			JsonNode[] nodeVersions = Iterators.toArray(resNode.at("/nodes").iterator(), JsonNode.class);

			for (JsonNode n : nodeVersions[0].get("plugins")) {
				if ("org.opensearch.security.OpenSearchSecurityPlugin".equals(n.get("name").asText())) {
					System.out.println("OpenSearch Security Version: " + n.get("version"));
					break;
                }
			}

		} else {
			System.out.println("ERR: Your cluster consists of zero nodes");
        }

        return 0;
    }

    private static RestHighLevelClient getRestHighLevelClient(SSLContext sslContext,
															  boolean nhnv,
															  String[] enabledProtocols,
															  String[] enabledCiphers,
															  String hostname,
															  int port) {

		final HostnameVerifier hnv = !nhnv ? new DefaultHostnameVerifier() : NoopHostnameVerifier.INSTANCE;

		String[] supportedProtocols = enabledProtocols.length > 0 ? enabledProtocols : null;
		String[] supportedCipherSuites = enabledCiphers.length > 0 ? enabledCiphers : null;

		HttpHost httpHost = new HttpHost(hostname, port, "https");

		RestClientBuilder restClientBuilder = RestClient.builder(httpHost)
				.setHttpClientConfigCallback(
						builder -> builder.setSSLStrategy(
								new SSLIOSessionStrategy(
										sslContext,
										supportedProtocols,
										supportedCipherSuites,
										hnv
								)
						)
				);
		return new RestHighLevelClient(restClientBuilder);
	}

    private static void validate(CommandLine line) throws ParseException {

        if(line.hasOption("ts") && line.hasOption("cacert")) {
            System.out.println("WARN: It makes no sense to specify -ts as well as -cacert");
        }
        
        if(line.hasOption("ks") && line.hasOption("cert")) {
            System.out.println("WARN: It makes no sense to specify -ks as well as -cert");
        }
        
        if(line.hasOption("ks") && line.hasOption("key")) {
            System.out.println("WARN: It makes no sense to specify -ks as well as -key");
        }
        
        if(line.hasOption("cd") && line.hasOption("rl")) {
            System.out.println("WARN: It makes no sense to specify -cd as well as -r");
        }
        
        if(line.hasOption("cd") && line.hasOption("f")) {
            System.out.println("WARN: It makes no sense to specify -cd as well as -f");
        }

        if(line.hasOption("cn") && line.hasOption("icl")) {
            throw new ParseException("Only set one of -cn or -icl");
        }

        if(line.hasOption("vc") && !line.hasOption("cd") && !line.hasOption("f")) {
            throw new ParseException("Specify at least -cd or -f together with vc");
        }

        if(!line.hasOption("vc") && !line.hasOption("ks") && !line.hasOption("cert") /*&& !line.hasOption("simple-auth")*/) {
            throw new ParseException("Specify at least -ks or -cert");
        }
        
        if(!line.hasOption("vc")  && !line.hasOption("mo") 
                && !line.hasOption("ts") && !line.hasOption("cacert")) {
            throw new ParseException("Specify at least -ts or -cacert");
        }
    }
}