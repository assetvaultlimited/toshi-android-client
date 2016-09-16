package com.bakkenbaeck.toshi.presenter;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import com.bakkenbaeck.toshi.R;
import com.bakkenbaeck.toshi.model.ActivityResultHolder;
import com.bakkenbaeck.toshi.model.LocalBalance;
import com.bakkenbaeck.toshi.util.LogUtil;
import com.bakkenbaeck.toshi.util.OnNextObserver;
import com.bakkenbaeck.toshi.view.BaseApplication;
import com.bakkenbaeck.toshi.view.activity.BarcodeScannerActivity;
import com.bakkenbaeck.toshi.view.activity.WithdrawActivity;
import com.bakkenbaeck.toshi.view.adapter.WalletAddressesAdapter;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.math.BigDecimal;

import rx.Subscriber;

import static android.app.Activity.RESULT_OK;

public class WithdrawPresenter implements Presenter<WithdrawActivity> {

    static final String INTENT_WALLET_ADDRESS = "wallet_address";
    static final String INTENT_WITHDRAW_AMOUNT = "withdraw_amount";

    private WithdrawActivity activity;
    private boolean firstTimeAttaching = true;
    private BigDecimal currentBalance = BigDecimal.ZERO;

    private final WalletAddressesAdapter previousAddressesAdapter = new WalletAddressesAdapter();

    @Override
    public void onViewAttached(final WithdrawActivity activity) {
        this.activity = activity;
        initButtons();
        initToolbar();
        initPreviousAddresses();

        if (firstTimeAttaching) {
            firstTimeAttaching = false;
            registerObservables();
        }
    }

    private void initButtons() {
        this.activity.getBinding().barcodeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View view) {
                showBarcodeActivity();
            }
        });

        this.activity.getBinding().sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View view) {
                handleSendClicked();
            }
        });

        this.activity.getBinding().walletAddress.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence charSequence, final int i, final int i1, final int i2) {}

            @Override
            public void onTextChanged(final CharSequence charSequence, final int i, final int i1, final int i2) {
                final boolean showFab = charSequence.length() > 0;
                if (showFab) {
                    activity.getBinding().sendButton.animate().alpha(1.0f).setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationStart(final Animator animation) {
                            super.onAnimationStart(animation);
                            activity.getBinding().sendButton.setVisibility(View.VISIBLE);
                        }
                    });
                } else {
                    activity.getBinding().sendButton.animate().alpha(0.0f).setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(final Animator animation) {
                            super.onAnimationEnd(animation);
                            activity.getBinding().sendButton.setVisibility(View.INVISIBLE);
                        }
                    });
                }
            }

            @Override
            public void afterTextChanged(final Editable editable) {}
        });
    }

    private void initToolbar() {
        final String title = this.activity.getResources().getString(R.string.withdraw__title);
        final Toolbar toolbar = this.activity.getBinding().toolbar;
        this.activity.setSupportActionBar(toolbar);
        this.activity.getSupportActionBar().setTitle(title);
        this.activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    private void initPreviousAddresses() {
        this.activity.getBinding().previousWallets.setAdapter(this.previousAddressesAdapter);
    }

    private final OnNextObserver<LocalBalance> newBalanceSubscriber = new OnNextObserver<LocalBalance>() {
        @Override
        public void onNext(final LocalBalance newBalance) {
            if (activity != null && newBalance != null) {
                activity.getBinding().balanceBar.setBalance(newBalance.unconfirmedBalanceString());
                tryPopulateAmountField(currentBalance, newBalance.confirmedBalanceString());
                currentBalance = newBalance.getConfirmedBalanceAsEth();
            }
        }
    };

    private void tryPopulateAmountField(final BigDecimal previousBalance, final String newBalanceAsEthString) {
        try {
            if (new BigDecimal(this.activity.getBinding().amount.getText().toString()).equals(previousBalance)) {
                this.activity.getBinding().amount.setText(newBalanceAsEthString);
                this.activity.getBinding().amount.setSelection(this.activity.getBinding().amount.getText().length());
            }
        } catch (final Exception ex) {
            // Do nothing -- user is editing the field
        }

    }

    @Override
    public void onViewDetached() {
        this.activity = null;
    }

    @Override
    public void onViewDestroyed() {
        unregisterObservable();
        this.activity = null;
    }

    public void handleActivityResult(final ActivityResultHolder activityResultHolder) {
        if (activityResultHolder.getResultCode() != RESULT_OK) {
            return;
        }

        tryParseBarcodeResult(activityResultHolder);
    }

    private void showBarcodeActivity() {
        new IntentIntegrator(this.activity)
                .setCaptureActivity(BarcodeScannerActivity.class)
                .setOrientationLocked(true)
                .setPrompt("")
                .setBeepEnabled(true)
                .initiateScan();
    }

    private void tryParseBarcodeResult(final ActivityResultHolder activityResultHolder) {
        final IntentResult result = IntentIntegrator.parseActivityResult(
                activityResultHolder.getRequestCode(),
                activityResultHolder.getResultCode(),
                activityResultHolder.getIntent());
        if(result == null || result.getContents() == null) {
            return;
        }

        this.activity.getBinding().walletAddress.setText(result.getContents());
    }

    private void handleSendClicked() {
        if (!validate()) {
            return;
        }
        final Intent intent = new Intent();
        intent.putExtra(INTENT_WALLET_ADDRESS, this.activity.getBinding().walletAddress.getText().toString());
        intent.putExtra(INTENT_WITHDRAW_AMOUNT, new BigDecimal(this.activity.getBinding().amount.getText().toString()));
        this.activity.setResult(RESULT_OK, intent);
        this.activity.finish();
        this.activity.overridePendingTransition(R.anim.enter_from_left, R.anim.exit_to_right);
    }

    private boolean validate() {
        try {
            final BigDecimal amountRequested = new BigDecimal(this.activity.getBinding().amount.getText().toString());

            if (amountRequested.compareTo(BigDecimal.ZERO) > 0 && amountRequested.compareTo(this.currentBalance) <= 0) {
                return true;
            }
        } catch (final NumberFormatException ex) {
            LogUtil.e(getClass(), ex.toString());
        }

        this.activity.getBinding().amount.setError(this.activity.getResources().getString(R.string.withdraw__amount_error));
        return false;
    }

    private void registerObservables() {
        this.previousAddressesAdapter.getPositionClicks().subscribe(this.clicksSubscriber);
        BaseApplication.get().getLocalBalanceManager().getObservable().subscribe(this.newBalanceSubscriber);
    }

    private void unregisterObservable() {
        this.clicksSubscriber.unsubscribe();
    }

    private final Subscriber<String> clicksSubscriber = new Subscriber<String>() {
        @Override
        public void onCompleted() {}

        @Override
        public void onError(final Throwable e) {}

        @Override
        public void onNext(final String clickedAddress) {
            activity.getBinding().walletAddress.setText(clickedAddress);
        }
    };
}
