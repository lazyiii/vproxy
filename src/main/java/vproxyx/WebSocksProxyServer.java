package vproxyx;

import vproxy.component.elgroup.EventLoopGroup;
import vproxy.component.proxy.ConnectorGen;
import vproxy.component.proxy.Proxy;
import vproxy.component.proxy.ProxyNetConfig;
import vproxy.connection.BindServer;
import vproxy.connection.Connection;
import vproxy.connection.Connector;
import vproxy.protocol.ProtocolHandler;
import vproxy.util.Callback;
import vproxy.util.Logger;
import vproxy.util.Tuple;
import vproxyx.websocks.WebSocksProtocolHandler;
import vproxyx.websocks.WebSocksProxyContext;
import vproxyx.websocks.WebSocksUtils;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@SuppressWarnings("unused")
public class WebSocksProxyServer {
    public static void main0(String[] args) throws Exception {
        Map<String, String> auth = new HashMap<>();
        int port = -1;
        boolean ssl = false;
        String pkcs12 = null;
        String pkcs12pswd = null;
        String domain = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String next = i == args.length - 1 ? null : args[i + 1];
            //noinspection IfCanBeSwitch
            if (arg.equals("listen")) {
                if (next == null) {
                    throw new IllegalArgumentException("`listen` should be followed with a port argument");
                }
                try {
                    port = Integer.parseInt(next);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("port is not a number");
                }
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("port is not valid");
                }
                ++i;
            } else if (arg.equals("auth")) {
                if (next == null) {
                    throw new IllegalArgumentException("`auth` should be followed with a sequence of `user:password`");
                }
                String[] pairs = next.split(",");
                for (String pair : pairs) {
                    String[] userpass = pair.split(":");
                    if (userpass.length != 2 || userpass[0].trim().isEmpty() || userpass[1].trim().isEmpty())
                        throw new IllegalArgumentException("invalid user:password pair: " + pair);
                    auth.put(userpass[0].trim(), userpass[1].trim());
                }
                ++i;
            } else if (arg.equals("ssl")) {
                ssl = true;
            } else if (arg.equals("pkcs12")) {
                if (next == null) {
                    throw new IllegalArgumentException("`pkcs12` should be followed with a pkcs12 file");
                }
                pkcs12 = next;
                ++i;
            } else if (arg.equals("pkcs12pswd")) {
                if (next == null) {
                    throw new IllegalArgumentException("`pkcs12pswd` should be followed with the password of pkcs12 file");
                }
                pkcs12pswd = next;
                ++i;
            } else if (arg.equals("domain")) {
                if (next == null) {
                    throw new IllegalArgumentException("`domain` should be followed with the domain name");
                }
                domain = next;
                ++i;
            } else
                throw new IllegalArgumentException("unknown argument: " + arg + ".\n" +
                    "argument: listen {} auth {} [ssl pkcs12 {} pkcs12pswd {}] [domain {}]\n" +
                    "examples: listen 443 auth alice:pasSw0rD ssl pkcs12 ~/my.p12 pkcs12pswd paSsWorD domain example.com\n" +
                    " [no ssl] listen 80 auth alice:pasSw0rD,bob:pAssW0Rd");
        }
        if (port == -1)
            throw new IllegalArgumentException("listening port is not specified. `listen $PORT`, " +
                "example: listen 443");
        if (auth.isEmpty())
            throw new IllegalArgumentException("authentication not specified. `auth $USER:$PASS[,$USER2:$PASS2[,...]]`, " +
                "example: auth alice:pasSw0rD,bob:PaSsw0Rd");
        if (ssl) {
            if (pkcs12 == null)
                throw new IllegalArgumentException("ssl mode is enabled, but pkcs12 cert+key file not specified. " +
                    "`pkcs12 $file_path`, example: pkcs12 ~/mycertandkey.p12");
            if (pkcs12pswd == null)
                throw new IllegalArgumentException("ssl mode is enabled, but password of pkcs12 file not specified. " +
                    "`pkcs12pswd $password`, example: pkcs12pswd pasSw0rD");
        } else {
            if (pkcs12 != null || pkcs12pswd != null)
                throw new IllegalArgumentException("pkcs12 info specified but no `ssl` flag set.");
        }
        if (pkcs12 != null && pkcs12.startsWith("~")) {
            pkcs12 = System.getProperty("user.home") + pkcs12.substring(1);
        }

        assert Logger.lowLevelDebug("listen: " + port);
        assert Logger.lowLevelDebug("auth: " + auth);
        assert Logger.lowLevelDebug("ssl: " + ssl);
        assert Logger.lowLevelDebug("pkcs12: " + pkcs12);
        assert Logger.lowLevelDebug("pkcs12pswd: " + pkcs12pswd);

        // init the listening server
        BindServer server = BindServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{0, 0, 0, 0}), port));

        // init event loops
        int threads = Math.min(4, Runtime.getRuntime().availableProcessors());
        int workers = threads;
        if (threads > 3) {
            workers -= 1; // one core for acceptor if there are at least 4 processors
        }
        // initiate the acceptor event loop(s)
        EventLoopGroup acceptor = new EventLoopGroup("acceptor-group");
        acceptor.add("acceptor-loop");
        // initiate the worker event loop(s)
        EventLoopGroup worker = new EventLoopGroup("worker-group");
        for (int i = 0; i < workers; ++i) {
            worker.add("worker-loop-" + i);
        }

        // build ssl engine supplier (or maybe null)
        Supplier<SSLEngine> engineSupplier = null;
        if (ssl) {
            // init the ssl context
            WebSocksUtils.initSslContext(pkcs12, pkcs12pswd,
                "PKCS12", true, false);

            // ssl params
            SSLParameters params = new SSLParameters();
            {
                params.setApplicationProtocols(new String[]{"http/1.1"});
                if (domain != null) {
                    String reg = "^" + domain.replaceAll("\\.", "\\\\.") + "$";
                    assert Logger.lowLevelDebug("the sni matcher regexp is " + reg);
                    params.setSNIMatchers(Collections.singletonList(SNIHostName.createSNIMatcher(reg)));
                }
            }

            // set some final variables for the lambda to use
            final String finalDomain = domain;
            final int finalPort = port;

            if (domain == null) {
                engineSupplier = () -> {
                    SSLEngine engine = WebSocksUtils.createEngine();
                    engine.setUseClientMode(false);
                    engine.setSSLParameters(params);
                    return engine;
                };
            } else {
                engineSupplier = () -> {
                    SSLEngine engine = WebSocksUtils.createEngine(finalDomain, finalPort);
                    engine.setUseClientMode(false);
                    engine.setSSLParameters(params);
                    return engine;
                };
            }
        }
        // init the proxy server
        WebSocksProtocolHandler webSocksProtocolHandler = new WebSocksProtocolHandler(auth, engineSupplier);
        ConnectorGen<WebSocksProxyContext> connGen = new ConnectorGen<>() {
            @Override
            public Type type() {
                return Type.handler;
            }

            @Override
            public ProtocolHandler<Tuple<WebSocksProxyContext, Callback<Connector, IOException>>> handler() {
                return webSocksProtocolHandler;
            }

            @Override
            public Connector genConnector(Connection accepted) {
                return null; // will not be called because type is `handler`
            }
        };
        Proxy proxy = new Proxy(
            new ProxyNetConfig()
                .setAcceptLoop(acceptor.next())
                .setInBufferSize(24576)
                .setOutBufferSize(24576)
                .setHandleLoopProvider(worker::next)
                .setServer(server)
                .setConnGen(connGen),
            s -> {
                // do nothing, won't happen
                // when terminating, user should simply kill this process and won't close server
            });

        // start the proxy server
        proxy.handle();

        Logger.alert("server started on " + port);
    }
}
