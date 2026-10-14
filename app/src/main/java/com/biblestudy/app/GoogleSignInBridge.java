package com.biblestudy.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * window.AndroidGoogleAuth — native Google sign-in for the installed app.
 *
 * Google deliberately blocks its OAuth web flow inside embedded WebViews,
 * so firebaseAuth.signInWithPopup() can never work here (and window.open()
 * returns null anyway, since this WebView doesn't support multiple windows).
 * Instead this uses Android's Credential Manager to get a Google ID token
 * natively, hands it back to the page, and the page exchanges it for a
 * Firebase session via firebaseAuth.signInWithCredential(). The Firebase
 * user/uid that comes out is identical to the one the browser build gets.
 *
 * The "server client ID" required below is the project's *Web* OAuth client
 * (Firebase Console -> Authentication -> Sign-in method -> Google -> Web SDK
 * configuration). It is NOT the Android client ID. The google-services
 * Gradle plugin already generates it as R.string.default_web_client_id from
 * the client_type 3 entry in google-services.json, so it is read from there
 * rather than pasted in — that way it stays correct if the JSON is ever
 * re-downloaded.
 */
public class GoogleSignInBridge {

    private final Activity activity;
    private final WebView webView;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public GoogleSignInBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    /**
     * The page calls this to decide whether to take the native path. It only
     * exists at all inside the installed app, so returning true is enough.
     */
    @JavascriptInterface
    public boolean isAvailable() {
        return true;
    }

    /**
     * Starts the account picker. Asynchronous: the result comes back to the
     * page through window.__onGoogleIdToken / window.__onGoogleSignInError.
     */
    @JavascriptInterface
    public void signIn() {
        // Called from the WebView's JS thread; Credential Manager needs to be
        // kicked off with an Activity context on the main thread.
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                start();
            }
        });
    }

    private void start() {
        final String serverClientId;
        try {
            serverClientId = activity.getString(R.string.default_web_client_id);
        } catch (Exception e) {
            fail("Missing default_web_client_id — the google-services plugin didn't generate it. "
                    + "Check that google-services.json has a client_type 3 (Web) OAuth client.");
            return;
        }

        // GetSignInWithGoogleOption is the "Sign in with Google button" flow:
        // it always shows the account chooser, including for users with no
        // Google account yet set up for this app. (GetGoogleIdOption, the
        // other option, is the one-tap bottom sheet and simply errors out
        // with "no credentials available" in that case — which would look
        // exactly like another mysterious silent failure.)
        GetSignInWithGoogleOption option =
                new GetSignInWithGoogleOption.Builder(serverClientId).build();

        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build();

        CredentialManager credentialManager = CredentialManager.create(activity);

        credentialManager.getCredentialAsync(
                activity,
                request,
                null,
                executor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        handleResponse(response);
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        // Includes the user simply backing out of the picker,
                        // which is a normal, non-error outcome.
                        String msg = e.getMessage();
                        fail(msg == null || msg.isEmpty()
                                ? "Google sign-in was cancelled."
                                : msg);
                    }
                });
    }

    private void handleResponse(GetCredentialResponse response) {
        Credential credential = response.getCredential();
        if (credential instanceof CustomCredential
                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        .equals(credential.getType())) {
            try {
                GoogleIdTokenCredential googleCredential =
                        GoogleIdTokenCredential.createFrom(credential.getData());
                String idToken = googleCredential.getIdToken();
                if (idToken == null || idToken.isEmpty()) {
                    fail("Google returned no ID token.");
                } else {
                    succeed(idToken);
                }
            } catch (Exception e) {
                fail("Couldn't read the Google credential: " + e.getMessage());
            }
        } else {
            fail("Unexpected credential type returned: " + credential.getType());
        }
    }

    private void succeed(final String idToken) {
        // ID tokens are JWTs (base64url + '.'), so they're safe to inline,
        // but quote them anyway rather than trusting that.
        eval("window.__onGoogleIdToken && window.__onGoogleIdToken(" + jsString(idToken) + ");");
    }

    private void fail(final String message) {
        eval("window.__onGoogleSignInError && window.__onGoogleSignInError(" + jsString(message) + ");");
    }

    private void eval(final String js) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private static String jsString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<':  sb.append("\\u003c"); break;
                case '>':  sb.append("\\u003e"); break;
                case '&':  sb.append("\\u0026"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append("\"").toString();
    }
}
