package com.codebutler.android_websockets;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import org.apache.http.*;
import org.apache.http.client.HttpResponseException;
import org.apache.http.message.BasicLineParser;
import org.apache.http.message.BasicNameValuePair;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@TargetApi(8)
public class WebSocketClient {
    private static final String TAG = "WebSocketClient";
    private int mSocketTag = -1;

    private URI                      mURI;
    private Listener                 mListener;
    private Socket                   mSocket;
    private Thread                   mThread;
    private static final HandlerThread mHandlerThread = new HandlerThread("websocket-thread");
    private Handler                  mHandler;
    private List<BasicNameValuePair> mExtraHeaders;
    private HybiParser               mParser;
    private String                   mProxyHost;
    private int                      mProxyPort;

    static final String ENABLED_CIPHERS[] = {
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
            "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
            "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
            "TLS_DHE_DSS_WITH_AES_128_CBC_SHA",
            "TLS_ECDHE_RSA_WITH_RC4_128_SHA",
            "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA",
            "TLS_RSA_WITH_AES_128_CBC_SHA",
            "TLS_RSA_WITH_AES_256_CBC_SHA",
            "SSL_RSA_WITH_3DES_EDE_CBC_SHA",
            "SSL_RSA_WITH_RC4_128_SHA",
            "SSL_RSA_WITH_RC4_128_MD5",
    };

    static final String ENABLED_PROTOCOLS[] = {
            "TLSv1.2", "TLSv1.1", "TLSv1"
    };

    private final Object mSendLock = new Object();

    private static TrustManager[] sTrustManagers;

    public static void setTrustManagers(TrustManager[] tm) {
        sTrustManagers = tm;
    }

    public WebSocketClient(URI uri, Listener listener, List<BasicNameValuePair> extraHeaders) {
        mURI          = uri;
        mListener = listener;
        mExtraHeaders = extraHeaders;
        mParser       = new HybiParser(this);

        if(!mHandlerThread.isAlive())
            mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    public Listener getListener() {
        return mListener;
    }

    public void connect() {
        if (mThread != null && mThread.isAlive()) {
            return;
        }

        mThread = new Thread(new Runnable() {
            @SuppressLint("NewApi")
			public void run() {
                try {
                    String secret = createSecret();

                    int port = (mURI.getPort() != -1) ? mURI.getPort() : (mURI.getScheme().equals("wss") ? 443 : 80);

                    String path = TextUtils.isEmpty(mURI.getPath()) ? "/" : mURI.getPath();
                    if (!TextUtils.isEmpty(mURI.getQuery())) {
                        path += "?" + mURI.getQuery();
                    }

                    String originScheme = mURI.getScheme().equals("wss") ? "https" : "http";
                    URI origin = new URI(originScheme, "//" + mURI.getHost(), null);

                    SocketFactory factory = mURI.getScheme().equals("wss") ? getSSLSocketFactory() : SocketFactory.getDefault();
                    if(mProxyHost != null && mProxyHost.length() > 0)
                        mSocket = SocketFactory.getDefault().createSocket(mProxyHost, mProxyPort);
                    else {
                        mSocket = factory.createSocket(mURI.getHost(), port);

                        if(mURI.getScheme().equals("wss")) {
                            SSLSocket s = (SSLSocket)mSocket;
                            try {
                                s.setEnabledProtocols(ENABLED_PROTOCOLS);
                            } catch (IllegalArgumentException e) {
                                //Not supported on older Android versions
                            }
                            try {
                                s.setEnabledCipherSuites(ENABLED_CIPHERS);
                            } catch (IllegalArgumentException e) {
                                //Not supported on older Android versions
                            }
                        }
                    }
                    if(Build.VERSION.SDK_INT >= 14 && mSocketTag > 0) {
                    	TrafficStats.setThreadStatsTag(mSocketTag);
                    	TrafficStats.tagSocket(mSocket);
                    }

                    PrintWriter out = new PrintWriter(mSocket.getOutputStream());
                    if(mProxyHost != null && mProxyHost.length() > 0 && mProxyPort > 0) {
                        out.print("CONNECT " + mURI.getHost() + ":" + port + " HTTP/1.1\r\n");
                        out.print("\r\n");
                        out.flush();
                        HybiParser.HappyDataInputStream stream = new HybiParser.HappyDataInputStream(mSocket.getInputStream());

                        // Read HTTP response status line.
                        StatusLine statusLine = parseStatusLine(readLine(stream));
                        if (statusLine == null) {
                            throw new HttpException("Received no reply from server.");
                        } else if (statusLine.getStatusCode() != HttpStatus.SC_OK) {
                            throw new HttpResponseException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
                        }

                        // Read HTTP response headers.
                        String line;
                        while (!TextUtils.isEmpty(line = readLine(stream)));
                        if(mURI.getScheme().equals("wss")) {
                            mSocket = getSSLSocketFactory().createSocket(mSocket, mURI.getHost(), port, false);
                            SSLSocket s = (SSLSocket)mSocket;
                            s.setEnabledProtocols(ENABLED_PROTOCOLS);
                            s.setEnabledCipherSuites(ENABLED_CIPHERS);
                            out = new PrintWriter(mSocket.getOutputStream());
                        }
                    }
                    out.print("GET " + path + " HTTP/1.1\r\n");
                    out.print("Upgrade: websocket\r\n");
                    out.print("Connection: Upgrade\r\n");
                    out.print("Host: " + mURI.getHost() + "\r\n");
                    out.print("Origin: " + origin.toString() + "\r\n");
                    out.print("Sec-WebSocket-Key: " + secret + "\r\n");
                    out.print("Sec-WebSocket-Version: 13\r\n");
                    out.print("Sec-WebSocket-Extensions: x-webkit-deflate-frame\r\n");
                    if (mExtraHeaders != null) {
                        for (NameValuePair pair : mExtraHeaders) {
                            out.print(String.format("%s: %s\r\n", pair.getName(), pair.getValue()));
                        }
                    }
                    out.print("\r\n");
                    out.flush();

                    HybiParser.HappyDataInputStream stream = new HybiParser.HappyDataInputStream(mSocket.getInputStream());

                    // Read HTTP response status line.
                    StatusLine statusLine = parseStatusLine(readLine(stream));
                    if (statusLine == null) {
                        throw new HttpException("Received no reply from server.");
                    } else if (statusLine.getStatusCode() != HttpStatus.SC_SWITCHING_PROTOCOLS) {
                        throw new HttpResponseException(statusLine.getStatusCode(), statusLine.getReasonPhrase());
                    }

                    // Read HTTP response headers.
                    String line;
                    boolean validated = false;

                    while (!TextUtils.isEmpty(line = readLine(stream))) {
                        Header header = parseHeader(line);
                        if (header.getName().equalsIgnoreCase("Sec-WebSocket-Accept")) {
                            String expected = createSecretValidation(secret);
                            String actual = header.getValue().trim();

                            if (!expected.equals(actual)) {
                                throw new HttpException("Bad Sec-WebSocket-Accept header value.");
                            }

                            validated = true;
                        } else if(header.getName().equalsIgnoreCase("Sec-WebSocket-Extensions")) {
                            if(header.getValue().trim().equalsIgnoreCase("x-webkit-deflate-frame"))
                                mParser.setDeflate(true);
                        }
                    }

                    if (!validated) {
                        throw new HttpException("No Sec-WebSocket-Accept header.");
                    }

                    mListener.onConnect();

                    // Now decode websocket frames.
                    mParser.start(stream);

                } catch (EOFException ex) {
                    Log.d(TAG, "WebSocket EOF!", ex);
                    mListener.onDisconnect(0, "EOF");

                } catch (SSLException ex) {
                    // Connection reset by peer
                    Log.d(TAG, "Websocket SSL error!", ex);
                    mListener.onDisconnect(0, "SSL");

                } catch (Exception ex) {
                    mListener.onError(ex);
                }
            }
        });
        mThread.start();
    }

    public void disconnect() {
        if (mSocket != null) {
            mHandler.post(new Runnable() {
                public void run() {
                    try {
                    	if(mSocket != null)
                    		mSocket.close();
                        mSocket = null;
                    } catch (IOException ex) {
                        Log.d(TAG, "Error while disconnecting", ex);
                        mListener.onError(ex);
                    }
                }
            });
        }
    }

    public void send(String data) {
        sendFrame(mParser.frame(data));
    }

    public void send(byte[] data) {
        sendFrame(mParser.frame(data));
    }

    private StatusLine parseStatusLine(String line) {
        if (TextUtils.isEmpty(line)) {
            return null;
        }
        return BasicLineParser.parseStatusLine(line, new BasicLineParser());
    }

    private Header parseHeader(String line) {
        return BasicLineParser.parseHeader(line, new BasicLineParser());
    }

    // Can't use BufferedReader because it buffers past the HTTP data.
    private String readLine(HybiParser.HappyDataInputStream reader) throws IOException {
        int readChar = reader.read();
        if (readChar == -1) {
            return null;
        }
        StringBuilder string = new StringBuilder("");
        while (readChar != '\n') {
            if (readChar != '\r') {
                string.append((char) readChar);
            }

            readChar = reader.read();
            if (readChar == -1) {
                return null;
            }
        }
        return string.toString();
    }

    private String createSecret() {
        byte[] nonce = new byte[16];
        for (int i = 0; i < 16; i++) {
            nonce[i] = (byte) (Math.random() * 256);
        }
        return Base64.encodeToString(nonce, Base64.DEFAULT).trim();
    }

    private String createSecretValidation(String secret) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((secret + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes());
            return Base64.encodeToString(md.digest(), Base64.DEFAULT).trim();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    void sendFrame(final byte[] frame) {
        mHandler.post(new Runnable() {
			@SuppressLint("NewApi")
			public void run() {
                try {
                    synchronized (mSendLock) {
                        if (mSocket == null) {
                            mListener.onError(new IllegalStateException("Socket not connected"));
                            return;
                        }
                        OutputStream outputStream = mSocket.getOutputStream();
                        outputStream.write(frame);
                        outputStream.flush();
                        if(Build.VERSION.SDK_INT >= 14)
                            TrafficStats.incrementOperationCount(1);
                	}
                } catch (IOException e) {
                    mListener.onError(e);
                }
            }
        });
    }

    public void setSocketTag(int tag) {
    	mSocketTag = tag;
        if(Build.VERSION.SDK_INT >= 14 && mSocketTag > 0 && mSocket != null) {
        	mHandler.post(new Runnable() {
                @TargetApi(14)
				public void run() {
                    try {
                    	TrafficStats.setThreadStatsTag(mSocketTag);
        				TrafficStats.tagSocket(mSocket);
					} catch (SocketException e) {
						mListener.onError(e);
					}
                }
        	});
        }
    }

    public void setProxy(String host, int port) {
        mProxyHost = host;
        mProxyPort = port;
    }
    
    public interface Listener {
        public void onConnect();
        public void onMessage(String message);
        public void onMessage(byte[] data);
        public void onDisconnect(int code, String reason);
        public void onError(Exception error);
    }

    private SSLSocketFactory getSSLSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, sTrustManagers, null);
        return context.getSocketFactory();
    }
}
