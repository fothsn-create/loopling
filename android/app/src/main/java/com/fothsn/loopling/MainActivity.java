package com.fothsn.loopling;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.JavascriptInterface;
import android.widget.EditText;
import android.widget.Toast;
import android.text.InputType;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Loopling's bundled/offline game, with native progress and Google Play one-time billing. */
public final class MainActivity extends Activity {
    private static final String BASE = "https://appassets.androidplatform.net/assets/";
    private static final String PRODUCT_ID = "full_game";
    private static final String SAVE_KEY = "loopling.save.v1";
    private static final String PREFS_NAME = "loopling_data";
    private static final String PREF_VERIFIED_OWNED = "verified_full_game";

    private WebView web;
    private BillingClient billing;
    private ProductDetails fullProduct;
    private ProductDetails.OneTimePurchaseOfferDetails fullOffer;
    private SharedPreferences preferences;
    private volatile boolean owned;
    private volatile boolean reviewerUnlocked = false;
    private long lastReviewerTap = 0L;
    private int reviewerTapCount = 0;
    private volatile boolean pending;
    private volatile String formattedPrice = "";
    private volatile boolean webLoaded = false;
    private OnBackInvokedCallback backCallback;

    @Override @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        owned = preferences.getBoolean(PREF_VERIFIED_OWNED, false);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        showImmersive();

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(20, 15, 51));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!isLocalAsset(uri)) return blocked();
                WebResourceResponse result = loader.shouldInterceptRequest(uri);
                return result != null ? result : blocked();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isLocalAsset(uri)) return false;
                // External links (e.g. Google's privacy policy) open outside this privileged WebView.
                if ("https".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (ActivityNotFoundException ignored) { }
                }
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (url.startsWith(BASE)) {
                    webLoaded = true;
                    sendStoreState(null);
                }
            }
        });
        web.addJavascriptInterface(new GameBridge(), "LooplingAndroid");
        // A quiet review-only access gesture. It passes touches through to the game.
        // On the menu, tap the very top-left corner seven times within seven seconds.
        // The gesture is disabled if no reviewer code has been configured at build time.
        web.setOnTouchListener((view, motion) -> {
            if (!BuildConfig.REVIEWER_CODE.isEmpty()
                    && motion.getActionMasked() == MotionEvent.ACTION_DOWN) {
                float edge = getResources().getDisplayMetrics().density * 64;
                if (motion.getX() <= edge && motion.getY() <= edge) {
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastReviewerTap > 7000) reviewerTapCount = 0;
                    lastReviewerTap = now;
                    if (++reviewerTapCount >= 7) {
                        reviewerTapCount = 0;
                        runOnUiThread(this::showReviewerCodeDialog);
                    }
                }
            }
            return false;
        });
        setContentView(web);
        web.loadUrl(BASE + "index.html");
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        connectBilling();
    }

    private boolean isLocalAsset(Uri uri) {
        return "https".equals(uri.getScheme())
            && "appassets.androidplatform.net".equals(uri.getHost())
            && uri.getPath() != null && uri.getPath().startsWith("/assets/");
    }

    private WebResourceResponse blocked() {
        WebResourceResponse res = new WebResourceResponse("text/plain", "UTF-8",
                new ByteArrayInputStream(new byte[0]));
        res.setStatusCodeAndReasonPhrase(403, "Forbidden");
        return res;
    }

    private void showImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private class GameBridge {
        @JavascriptInterface public String load(String key) {
            return SAVE_KEY.equals(key) ? preferences.getString(SAVE_KEY, null) : null;
        }

        @JavascriptInterface public void save(String key, String value) {
            if (SAVE_KEY.equals(key) && value != null && value.length() < 2_000_000) {
                // Commit synchronously; the game explicitly requires saves to survive fast exits.
                preferences.edit().putString(SAVE_KEY, value).commit();
            }
        }

        @JavascriptInterface public String store() { return storeJson(null).toString(); }

        @JavascriptInterface public void buy() { runOnUiThread(() -> beginPurchase()); }

        @JavascriptInterface public void vibrate(int millis) {
            if (millis <= 0) return;
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long duration = Math.min(millis, 200);
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(
                        VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                else vibrator.vibrate(duration);
            }
        }

        @JavascriptInterface public void keepAwake(boolean stayAwake) {
            runOnUiThread(() -> {
                if (stayAwake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
    }

    private void showReviewerCodeDialog() {
        EditText code = new EditText(this);
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        code.setHint("Reviewer access code");
        // The code is set at build time using a PRIVATE CI secret; never commit it.
        new AlertDialog.Builder(this)
                .setTitle("Loopling reviewer access")
                .setMessage("Enter the access code provided to Google Play reviewers.")
                .setView(code)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    byte[] candidate = code.getText().toString().trim().getBytes(StandardCharsets.UTF_8);
                    byte[] expected = BuildConfig.REVIEWER_CODE.getBytes(StandardCharsets.UTF_8);
                    if (MessageDigest.isEqual(candidate, expected)) {
                        reviewerUnlocked = true;
                        sendStoreState(null);
                        Toast.makeText(this, "Full game unlocked for this session", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Code not recognised", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private JSONObject storeJson(String event) {
        JSONObject result = new JSONObject();
        try {
            if (!formattedPrice.isEmpty()) result.put("price", formattedPrice);
            result.put("owned", owned || reviewerUnlocked);
            result.put("pending", pending);
            if (event != null) result.put("event", event);
        } catch (JSONException ignored) { }
        return result;
    }

    private void sendStoreState(String event) {
        runOnUiThread(() -> {
            if (web == null || !webLoaded) return;
            // JSONObject.toString() produces literal JSON: never concatenate untrusted JS source.
            web.evaluateJavascript("if(window.Loopling){window.Loopling.store(" +
                    storeJson(event).toString() + ");}", null);
        });
    }

    private void connectBilling() {
        billing = BillingClient.newBuilder(this)
                .setListener((result, purchases) -> {
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
                        examinePurchases(purchases, false);
                    } else if (result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
                        sendStoreState("cancelled");
                    } else {
                        sendStoreState("error");
                    }
                })
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
        billing.startConnection(new BillingClientStateListener() {
            @Override public void onBillingServiceDisconnected() {
                // Auto-service reconnection handles subsequent API calls.
            }

            @Override public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
                queryProduct();
                refreshPurchases();
            }
        });
    }

    private void queryProduct() {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()))
                .build();
        billing.queryProductDetailsAsync(params, (BillingResult result, QueryProductDetailsResult products) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) return;
            for (ProductDetails product : products.getProductDetailsList()) {
                if (!PRODUCT_ID.equals(product.getProductId())) continue;
                fullProduct = product;
                List<ProductDetails.OneTimePurchaseOfferDetails> offers = product.getOneTimePurchaseOfferDetailsList();
                if (offers != null && !offers.isEmpty()) fullOffer = offers.get(0);
                else fullOffer = product.getOneTimePurchaseOfferDetails();
                if (fullOffer != null) formattedPrice = fullOffer.getFormattedPrice();
                sendStoreState(null);
                break;
            }
        });
    }

    private void refreshPurchases() {
        if (billing == null || !billing.isReady()) return;
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build();
        billing.queryPurchasesAsync(params, (result, purchases) -> {
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                examinePurchases(purchases, true);
            }
            // Don't revoke offline cached ownership on a billing/network error.
        });
    }

    private void examinePurchases(List<Purchase> purchases, boolean isAuthoritativeQuery) {
        boolean purchased = false;
        boolean awaitingPayment = false;
        for (Purchase purchase : purchases) {
            if (!purchase.getProducts().contains(PRODUCT_ID)) continue;
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                purchased = true;
                if (!purchase.isAcknowledged()) acknowledge(purchase);
            } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                awaitingPayment = true;
            }
        }
        if (isAuthoritativeQuery) {
            owned = purchased;
            preferences.edit().putBoolean(PREF_VERIFIED_OWNED, purchased).apply();
        } else if (purchased) {
            owned = true;
            preferences.edit().putBoolean(PREF_VERIFIED_OWNED, true).apply();
        }
        pending = awaitingPayment;
        sendStoreState(null);
    }

    private void acknowledge(Purchase purchase) {
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken()).build();
        billing.acknowledgePurchase(params, result -> {
            // If this fails, the next query retries acknowledgement.
        });
    }

    private void beginPurchase() {
        if (owned) { sendStoreState(null); return; }
        if (billing == null || !billing.isReady() || fullProduct == null || fullOffer == null) {
            sendStoreState("unavailable");
            return;
        }
        BillingFlowParams.ProductDetailsParams productParams =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(fullProduct)
                    .setOfferToken(fullOffer.getOfferToken())
                    .build();
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(productParams))
                .build();
        BillingResult result = billing.launchBillingFlow(this, params);
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) sendStoreState("unavailable");
    }

    @Override protected void onResume() {
        super.onResume();
        showImmersive();
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("if(window.Loopling) window.Loopling.show();", null);
        }
        refreshPurchases();
    }

    @Override protected void onPause() {
        if (web != null) {
            web.evaluateJavascript("if(window.Loopling) window.Loopling.hide();", null);
            web.onPause();
        }
        super.onPause();
    }

    private void handleBack() {
        if (web == null) { finish(); return; }
        web.evaluateJavascript("!!(window.Loopling && window.Loopling.back())", result -> {
            if (!"true".equals(result)) finish();
        });
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        handleBack();
    }

    @Override protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (billing != null) billing.endConnection();
        if (web != null) {
            web.removeJavascriptInterface("LooplingAndroid");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
