package com.byteowls.capacitor.oauth2;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.Nullable;
import android.util.Log;
import com.byteowls.capacitor.oauth2.handler.AccessTokenCallback;
import com.byteowls.capacitor.oauth2.handler.OAuth2CustomHandler;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;
import net.openid.appauth.TokenResponse;

@NativePlugin(requestCodes = { OAuth2ClientPlugin.RC_OAUTH }, name = "OAuth2Client")
public class OAuth2ClientPlugin extends Plugin {

    static final int RC_OAUTH = 2000;

    private static final String PARAM_APP_ID = "appId";
    private static final String PARAM_ANDROID_APP_ID = "android.appId";
    private static final String PARAM_RESPONSE_TYPE = "responseType";
    private static final String PARAM_ANDROID_RESPONSE_TYPE = "android.responseType";
    private static final String PARAM_ACCESS_TOKEN_ENDPOINT = "accessTokenEndpoint";
    private static final String PARAM_AUTHORIZATION_BASE_URL = "authorizationBaseUrl";
    private static final String PARAM_ANDROID_CUSTOM_HANDLER_CLASS = "android.customHandlerClass";
    private static final String PARAM_ANDROID_CUSTOM_SCHEME = "android.customScheme";
    private static final String PARAM_SCOPE = "scope";
    private static final String PARAM_STATE = "state";
    public static final String PARAM_RESOURCE_URL = "resourceUrl";

    private AuthorizationService authService;
    private AuthState authState;

    public OAuth2ClientPlugin() {}

    @PluginMethod()
    public void authenticate(final PluginCall call) {
        disposeAuthService();
        String customHandlerClassname = ConfigUtils.getCallParam(String.class, call, PARAM_ANDROID_CUSTOM_HANDLER_CLASS);

        if (customHandlerClassname != null && customHandlerClassname.length() > 0) {
            try {
                Class<OAuth2CustomHandler> handlerClass = (Class<OAuth2CustomHandler>) Class.forName(customHandlerClassname);
                OAuth2CustomHandler handler = handlerClass.newInstance();
                handler.getAccessToken(getActivity(), call, new AccessTokenCallback() {
                    @Override
                    public void onSuccess(String accessToken) {
                        new ResourceUrlAsyncTask(call, getLogTag()).execute(accessToken);
                    }

                    @Override
                    public void onCancel() {
                        call.reject("Login canceled!");
                    }

                    @Override
                    public void onError(Exception error) {
                        Log.e(getLogTag(), "Login failed!", error);
                        call.reject("Login failed!");
                    }
                });
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                Log.e(getLogTag(), "Custom handler problem", e);
            }
        } else {
            String appId = ConfigUtils.getCallParam(String.class,call, PARAM_APP_ID);
            String androidAppId = ConfigUtils.getCallParam(String.class, call, PARAM_ANDROID_APP_ID);
            if (androidAppId != null && !androidAppId.isEmpty()) {
                appId = androidAppId;
            }

            if (appId == null || appId.length() == 0) {
                call.reject("Option '"+PARAM_APP_ID+"' or '"+PARAM_ANDROID_APP_ID+"' is required!");
                return;
            }

            String baseUrl = ConfigUtils.getCallParam(String.class, call, PARAM_AUTHORIZATION_BASE_URL);
            if (baseUrl == null || baseUrl.length() == 0) {
                call.reject("Option '"+PARAM_AUTHORIZATION_BASE_URL+"' is required!");
                return;
            }
            String accessTokenEndpoint = ConfigUtils.getCallParam(String.class, call, PARAM_ACCESS_TOKEN_ENDPOINT); // placeholder
            if (accessTokenEndpoint == null || accessTokenEndpoint.length() == 0) {
                call.reject("Option '"+PARAM_ACCESS_TOKEN_ENDPOINT+"' is required!");
                return;
            }
            String customScheme = ConfigUtils.getCallParam(String.class, call, PARAM_ANDROID_CUSTOM_SCHEME);
            if (customScheme == null || customScheme.length() == 0) {
                call.reject("Option '"+ PARAM_ANDROID_CUSTOM_SCHEME +"' is required!");
                return;
            }

            String responseType = ConfigUtils.getCallParam(String.class, call, PARAM_RESPONSE_TYPE);
            String androidResponseType = ConfigUtils.getCallParam(String.class, call, PARAM_ANDROID_RESPONSE_TYPE);
            if (androidResponseType != null && !androidResponseType.isEmpty()) {
                responseType = androidResponseType;
            }

            if (ResponseTypeValues.CODE.equals(responseType)) {
                Log.i(getLogTag(), "Code flow with PKCE is not supported yet");
            } else {
                responseType = ResponseTypeValues.TOKEN;
            }

            AuthorizationServiceConfiguration config = new AuthorizationServiceConfiguration(
                Uri.parse(baseUrl),
                Uri.parse(accessTokenEndpoint)
            );

            if (this.authState == null) {
                this.authState = new AuthState(config);
            }

            AuthorizationRequest.Builder builder = new AuthorizationRequest.Builder(
                config,
                appId,
                responseType,
                Uri.parse(customScheme)
            );

            AuthorizationRequest req = builder
                .setScope(call.getString(PARAM_SCOPE))
                .setState(call.getString(PARAM_STATE))
                .build();

            this.authService = new AuthorizationService(getContext());
            Intent authIntent = this.authService.getAuthorizationRequestIntent(req);

            startActivityForResult(call, authIntent, RC_OAUTH);
        }
    }

    @PluginMethod()
    public void logout(final PluginCall call) {
        String customHandlerClassname = ConfigUtils.getCallParam(String.class, call, PARAM_ANDROID_CUSTOM_HANDLER_CLASS);
        if (customHandlerClassname != null && customHandlerClassname.length() > 0) {
            try {
                Class<OAuth2CustomHandler> handlerClass = (Class<OAuth2CustomHandler>) Class.forName(customHandlerClassname);
                OAuth2CustomHandler handler = handlerClass.newInstance();
                boolean successful = handler.logout(call);
                if (successful) {
                    call.resolve();
                } else {
                    call.reject("Logout was not successful");
                }
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                Log.e(getLogTag(), "Custom handler problem", e);
            }
        } else {
            if (this.authService != null) {
                this.authService.dispose();
            }
            if (this.authState != null) {
                this.authState = null;
            }
            this.discardAuthState();
        }
    }

    @Override
    protected void handleOnActivityResult(int requestCode, int resultCode, Intent data) {
        super.handleOnActivityResult(requestCode, resultCode, data);
        if (RC_OAUTH == requestCode) {
            final PluginCall savedCall = getSavedCall();
            if (savedCall == null) {
                return;
            }

            AuthorizationResponse response = AuthorizationResponse.fromIntent(data);
            AuthorizationException error = AuthorizationException.fromIntent(data);
            this.authState.update(response, error);

            // get authorization code
            if (response != null) {
                this.authService = new AuthorizationService(getContext());
                this.authService.performTokenRequest(response.createTokenExchangeRequest(),
                    new AuthorizationService.TokenResponseCallback() {
                        @Override
                        public void onTokenRequestCompleted(@Nullable TokenResponse response, @Nullable AuthorizationException ex) {
                            if (response != null) {
                                authState.update(response, ex);
                                authState.performActionWithFreshTokens(authService, new AuthState.AuthStateAction() {
                                    @Override
                                    public void execute(@Nullable String accessToken, @Nullable String idToken, @Nullable AuthorizationException ex) {
                                        new ResourceUrlAsyncTask(savedCall, getLogTag()).execute(accessToken);
                                    }
                                });
                            } else {
                                savedCall.reject("No authToken retrieved!");
                            }
                        }
                    });
            }
        }
    }

    @Override
    protected void handleOnStop() {
        super.handleOnStop();
        disposeAuthService();
    }

    private void disposeAuthService() {
        if (authService != null) {
            authService.dispose();
            authService = null;
        }
    }

    public void discardAuthState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            getContext().deleteSharedPreferences(getLogTag());
        }
    }

}
