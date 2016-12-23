package com.bakkenbaeck.token.manager;


import com.bakkenbaeck.token.BuildConfig;
import com.bakkenbaeck.token.R;
import com.bakkenbaeck.token.crypto.HDWallet;
import com.bakkenbaeck.token.crypto.signal.SignalPreferences;
import com.bakkenbaeck.token.crypto.signal.SignalService;
import com.bakkenbaeck.token.crypto.signal.model.OutgoingMessage;
import com.bakkenbaeck.token.crypto.signal.store.ProtocolStore;
import com.bakkenbaeck.token.crypto.signal.store.SignalTrustStore;
import com.bakkenbaeck.token.util.LogUtil;
import com.bakkenbaeck.token.util.OnNextSubscriber;
import com.bakkenbaeck.token.view.BaseApplication;

import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.signalservice.api.SignalServiceMessagePipe;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import rx.Observable;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;

public final class SignalManager {

    private final PublishSubject<String> receiveMessageSubject = PublishSubject.create();
    private final PublishSubject<OutgoingMessage> sendMessageSubject = PublishSubject.create();
    private final PublishSubject<OutgoingMessage> failedMessageSubject = PublishSubject.create();

    private SignalService signalService;
    private HDWallet wallet;
    private SignalTrustStore trustStore;
    private ProtocolStore protocolStore;
    private SignalServiceMessagePipe messagePipe;
    private String userAgent;
    private boolean receiveMessages;

    public final SignalManager init(final HDWallet wallet) {
        this.wallet = wallet;
        this.userAgent = "Android " + BuildConfig.APPLICATION_ID + " - " + BuildConfig.VERSION_NAME +  ":" + BuildConfig.VERSION_CODE;
        new Thread(new Runnable() {
            @Override
            public void run() {
                initSignalManager();
            }
        }).start();

        return this;
    }

    private void initSignalManager() {
        generateStores();
        registerIfNeeded();
        attachSubscribers();
    }

    private void generateStores() {
        this.protocolStore = new ProtocolStore().init();
        this.trustStore = new SignalTrustStore();
        this.signalService = new SignalService(this.trustStore, this.wallet, this.protocolStore, this.userAgent);
    }

    private void registerIfNeeded() {
        if (!haveRegisteredWithServer()) {
            registerWithServer();
        }
    }

    private void registerWithServer() {
        this.signalService.registerKeys(this.protocolStore);
        SignalPreferences.setRegisteredWithServer();
    }

    private boolean haveRegisteredWithServer() {
        return SignalPreferences.getRegisteredWithServer();
    }

    private void attachSubscribers() {
        this.sendMessageSubject
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .subscribe(new OnNextSubscriber<OutgoingMessage>() {
            @Override
            public void onNext(final OutgoingMessage message) {
                sendMessageToBackend(message);
            }
        });

        receiveMessagesAsync();
    }



    public final void sendMessage(final OutgoingMessage message) {
        this.sendMessageSubject.onNext(message);
    }

    public final Observable<OutgoingMessage> getFailedMessagesObservable() {
        return this.failedMessageSubject.asObservable();
    }

    public final Observable<String> getReceiveMessagesObservable() {
        return this.receiveMessageSubject.asObservable();
    }

    private void sendMessageToBackend(final OutgoingMessage message) {
        final SignalServiceMessageSender messageSender = new SignalServiceMessageSender(
                BaseApplication.get().getResources().getString(R.string.chat_url),
                this.trustStore,
                this.wallet.getAddress(),
                this.protocolStore.getPassword(),
                this.protocolStore,
                this.userAgent,
                null
        );
        try {
            messageSender.sendMessage(
                    new SignalServiceAddress(message.getAddress()),
                    SignalServiceDataMessage.newBuilder()
                            .withBody(message.getBody())
                            .build());
        } catch (final UntrustedIdentityException | IOException ex) {
            LogUtil.error(getClass(), ex.toString());
            this.failedMessageSubject.onNext(message);
        }
    }

    public final void disconnect() {
        this.receiveMessages = false;
        if (this.messagePipe != null) {
            this.messagePipe.shutdown();
        }
    }

    private void receiveMessagesAsync() {
        this.receiveMessages = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (receiveMessages) {
                    receiveMessages();
                }
            }
        }).start();
    }

    private void receiveMessages() {
        final SignalServiceMessageReceiver messageReceiver = new SignalServiceMessageReceiver(
                BaseApplication.get().getResources().getString(R.string.chat_url),
                this.trustStore,
                this.wallet.getAddress(),
                this.protocolStore.getPassword(),
                this.protocolStore.getSignalingKey(),
                this.userAgent);
        final SignalServiceAddress localAddress = new SignalServiceAddress(this.wallet.getAddress());
        final SignalServiceCipher cipher = new SignalServiceCipher(localAddress, this.protocolStore);


        if (this.messagePipe == null) {
            this.messagePipe = messageReceiver.createMessagePipe();
        }

        try {
            final SignalServiceEnvelope envelope = messagePipe.read(10, TimeUnit.SECONDS);
            final SignalServiceContent message = cipher.decrypt(envelope);
            final SignalServiceDataMessage dataMessage = message.getDataMessage().get();
            if (dataMessage != null) {
                final String messageBody = dataMessage.getBody().get();
                this.receiveMessageSubject.onNext(messageBody);
            }
        } catch (final IllegalStateException | InvalidKeyException | InvalidKeyIdException | DuplicateMessageException | InvalidVersionException | LegacyMessageException | InvalidMessageException | NoSessionException | org.whispersystems.libsignal.UntrustedIdentityException | IOException | TimeoutException e) {
            LogUtil.e(getClass(), "receiveMessage: " + e.toString());
        }
    }

}