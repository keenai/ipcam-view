package com.github.niqdev.mjpeg;

import android.net.Network;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.Pair;
import android.text.TextUtils;
import android.util.Log;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

//import rx.Observable;
//import rx.android.schedulers.AndroidSchedulers;
//import rx.schedulers.Schedulers;

/**
 * A library wrapper for handle mjpeg streams.
 *
 * @see
 * <ul>
 *     <li><a href="https://bitbucket.org/neuralassembly/simplemjpegview">simplemjpegview</a></li>
 *     <li><a href="https://code.google.com/archive/p/android-camera-axis">android-camera-axis</a></li>
 * </ul>
 */
public class Mjpeg {
    private static final String TAG = Mjpeg.class.getSimpleName();

    private static java.net.CookieManager msCookieManager = new java.net.CookieManager();

    /**
     * Library implementation type
     */
    public enum Type {
        DEFAULT, NATIVE
    }

    private final Type type;
    private OkHttpClient okClient = null;
    private Network network;
    
    private boolean sendConnectionCloseHeader = false;

    private Mjpeg(Type type) {
        if (type == null) {
            throw new IllegalArgumentException("null type not allowed");
        }
        this.type = type;
    }

    /**
     * Uses {@link Type#DEFAULT} implementation.
     *
     * @return Mjpeg instance
     */
    public static Mjpeg newInstance() {
        return new Mjpeg(Type.DEFAULT);
    }

    /**
     * Choose among {@link com.github.niqdev.mjpeg.Mjpeg.Type} implementations.
     *
     * @return Mjpeg instance
     */
    public static Mjpeg newInstance(Type type) {
        return new Mjpeg(type);
    }

    /**
     * Configure authentication.
     *
     * @param username credential
     * @param password credential
     * @return Mjpeg instance
     */
    public Mjpeg credential(String username, String password) {
        if (!TextUtils.isEmpty(username) && !TextUtils.isEmpty(password)) {
            Authenticator.setDefault(new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password.toCharArray());
                }
            });
        }
        return this;
    }

    /**
     * Configure cookies.
     *
     * @param cookie cookie string
     * @return Mjpeg instance
     */
    public Mjpeg addCookie(String cookie)  {
        if(!TextUtils.isEmpty(cookie)) {
            msCookieManager.getCookieStore().add(null,HttpCookie.parse(cookie).get(0));
        }
        return this;
    }

    /**
     * Send a "Connection: close" header to fix 
     * <code>java.net.ProtocolException: Unexpected status line</code>
     * 
     * @return Observable Mjpeg stream
     */
    public Mjpeg sendConnectionCloseHeader() {
        sendConnectionCloseHeader = true;
        return this;
    }


    @NonNull
    private Observable<MjpegInputStream> connect(String url) {
        return Observable.defer(() -> {
            try {
                HttpURLConnection urlConnection;
                if (okClient == null || network == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    urlConnection = (HttpURLConnection) new URL(url).openConnection();
                } else {
                    urlConnection = new OkUrlFactory(new OkHttpClient().setSocketFactory(network.getSocketFactory())).open(new URL(url));
                }
                urlConnection.setRequestProperty("Cache-Control", "no-cache");
                if (sendConnectionCloseHeader) {
                    urlConnection.setRequestProperty("Connection", "close");
                }

                InputStream inputStream = urlConnection.getInputStream();
                switch (type) {
                    // handle multiple implementations
                    case DEFAULT:
                        return Observable.just(new MjpegInputStreamDefault(inputStream));
                    case NATIVE:
                        return Observable.just(new MjpegInputStreamNative(inputStream));
                }
                throw new IllegalStateException("invalid type");
            } catch (IOException e) {
                Log.e(TAG, "error during connection", e);
                return Observable.error(e);
            }
        });
    }

    @NonNull
    private Observable<MjpegInputStream> connect(String url, List<Pair<String, String>> httpParams,
                                                 @Nullable JSONObject postParameters) {
        return Observable.defer(() -> {
            try {
                byte[] data = postParameters != null ? postParameters.toString().getBytes("UTF-8") : null;
                System.out.println("Open connection: " + url);
                HttpURLConnection urlConnection;
                if (okClient == null || network == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    urlConnection = (HttpURLConnection) new URL(url).openConnection();
                } else {
                    urlConnection = new OkUrlFactory(new OkHttpClient().setSocketFactory(network.getSocketFactory())).open(new URL(url));
                }
                urlConnection.setRequestProperty("Cache-Control", "no-cache");
                if (this.sendConnectionCloseHeader) {
                    urlConnection.setRequestProperty("Connection", "close");
                }

                Iterator<Pair<String, String>> var6 = httpParams.iterator();

                while(var6.hasNext()) {
                    Pair<String, String> pair = var6.next();
                    urlConnection.setRequestProperty(pair.first, pair.second);
                }

                if (data != null) {
                    System.out.println("Output post info: " + postParameters.toString());
                    urlConnection.setDoOutput(true);
                    urlConnection.setFixedLengthStreamingMode(data.length);
                    OutputStream os = urlConnection.getOutputStream();
                    os.write(data);
                    os.flush();
                }

                System.out.println("Grab input stream");
                InputStream inputStream = urlConnection.getInputStream();
                switch(this.type) {
                case DEFAULT:
                    System.out.println("Setup DEFAULT mjpeg input stream");
                    return Observable.just(new MjpegInputStreamDefault(inputStream));
                case NATIVE:
                    System.out.println("Setup NATIVE mjpeg input stream");
                    return Observable.just(new MjpegInputStreamNative(inputStream));
                default:
                    throw new IllegalStateException("invalid type");
                }
            } catch (IOException var8) {
                Log.e(TAG, "error during connection", var8);
                return Observable.error(var8);
            }
        });
    }

    /**
     * Connect to a Mjpeg stream.
     *
     * @param url source
     * @return Observable Mjpeg stream
     */
    public Observable<MjpegInputStream> open(String url) {
        return connect(url)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread());
    }

    public void setClient(OkHttpClient client, Network network) {
        this.okClient = client;
        this.network = network;
    }

    /**
     * Connect to a Mjpeg stream.
     *
     * @param url source
     * @param timeout in seconds
     * @return Observable Mjpeg stream
     */
    public Observable<MjpegInputStream> open(String url, int timeout) {
        return connect(url)
            .timeout(timeout, TimeUnit.SECONDS)
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * Connect to a Mjpeg stream.
     *
     * @param url source
     * @return Observable Mjpeg stream
     */
    public Observable<MjpegInputStream> open(String url, List<Pair<String, String>> httpParams, JSONObject postParams) {
        return this.connect(url, httpParams, postParams)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * Connect to a Mjpeg stream.
     *
     * @param url source
     * @param timeout in seconds
     * @return Observable Mjpeg stream
     */
    public Observable<MjpegInputStream> open(String url, List<Pair<String, String>> httpParams, 
                                             JSONObject postParams, int timeout) {
        return this.connect(url, httpParams, postParams)
                    .timeout((long)timeout, TimeUnit.SECONDS)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread());
    }

}
