package de.pixart.messenger.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import net.java.otr4j.OtrException;
import net.java.otr4j.session.Session;

import de.pixart.messenger.Config;
import de.pixart.messenger.R;
import de.pixart.messenger.entities.Account;
import de.pixart.messenger.entities.Contact;
import de.pixart.messenger.entities.Conversation;
import de.pixart.messenger.services.XmppConnectionService;
import de.pixart.messenger.utils.CryptoHelper;
import de.pixart.messenger.utils.XmppUri;
import rocks.xmpp.addr.Jid;

public class VerifyOTRActivity extends XmppActivity implements XmppConnectionService.OnConversationUpdate {

    public static final String ACTION_VERIFY_CONTACT = "verify_contact";
    public static final int MODE_SCAN_FINGERPRINT = -0x0502;
    public static final int MODE_ASK_QUESTION = 0x0503;
    public static final int MODE_ANSWER_QUESTION = 0x0504;
    public static final int MODE_MANUAL_VERIFICATION = 0x0505;

    private LinearLayout mManualVerificationArea;
    private LinearLayout mSmpVerificationArea;
    private TextView mRemoteFingerprint;
    private TextView mYourFingerprint;
    private TextView mVerificationExplain;
    private TextView mStatusMessage;
    private TextView mSharedSecretHint;
    private EditText mSharedSecretHintEditable;
    private EditText mSharedSecretSecret;
    private Button mLeftButton;
    private Button mRightButton;
    private Account mAccount;
    private Conversation mConversation;
    private int mode = MODE_MANUAL_VERIFICATION;
    private XmppUri mPendingUri = null;

    private DialogInterface.OnClickListener mVerifyFingerprintListener = new DialogInterface.OnClickListener() {

        @Override
        public void onClick(DialogInterface dialogInterface, int click) {
            mConversation.verifyOtrFingerprint();
            xmppConnectionService.syncRosterToDisk(mConversation.getAccount());
            Toast.makeText(VerifyOTRActivity.this, R.string.verified, Toast.LENGTH_SHORT).show();
            finish();
        }
    };

    private View.OnClickListener mCreateSharedSecretListener = new View.OnClickListener() {
        @Override
        public void onClick(final View view) {
            if (isAccountOnline()) {
                final String question = mSharedSecretHintEditable.getText().toString();
                final String secret = mSharedSecretSecret.getText().toString();
                if (question.trim().isEmpty()) {
                    mSharedSecretHintEditable.requestFocus();
                    mSharedSecretHintEditable.setError(getString(R.string.shared_secret_hint_should_not_be_empty));
                } else if (secret.trim().isEmpty()) {
                    mSharedSecretSecret.requestFocus();
                    mSharedSecretSecret.setError(getString(R.string.shared_secret_can_not_be_empty));
                } else {
                    mSharedSecretSecret.setError(null);
                    mSharedSecretHintEditable.setError(null);
                    initSmp(question, secret);
                    updateView();
                }
            }
        }
    };
    private View.OnClickListener mCancelSharedSecretListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (isAccountOnline()) {
                abortSmp();
                updateView();
            }
        }
    };
    private View.OnClickListener mRespondSharedSecretListener = new View.OnClickListener() {

        @Override
        public void onClick(View view) {
            if (isAccountOnline()) {
                final String question = mSharedSecretHintEditable.getText().toString();
                final String secret = mSharedSecretSecret.getText().toString();
                respondSmp(question, secret);
                updateView();
            }
        }
    };
    private View.OnClickListener mRetrySharedSecretListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            mConversation.smp().status = Conversation.Smp.STATUS_NONE;
            mConversation.smp().hint = null;
            mConversation.smp().secret = null;
            updateView();
        }
    };
    private View.OnClickListener mFinishListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            mConversation.smp().status = Conversation.Smp.STATUS_NONE;
            finish();
        }
    };

    protected boolean initSmp(final String question, final String secret) {
        final Session session = mConversation.getOtrSession();
        if (session != null) {
            try {
                session.initSmp(question, secret);
                mConversation.smp().status = Conversation.Smp.STATUS_WE_REQUESTED;
                mConversation.smp().secret = secret;
                mConversation.smp().hint = question;
                return true;
            } catch (OtrException e) {
                return false;
            }
        } else {
            return false;
        }
    }

    protected boolean abortSmp() {
        final Session session = mConversation.getOtrSession();
        if (session != null) {
            try {
                session.abortSmp();
                mConversation.smp().status = Conversation.Smp.STATUS_NONE;
                mConversation.smp().hint = null;
                mConversation.smp().secret = null;
                return true;
            } catch (OtrException e) {
                return false;
            }
        } else {
            return false;
        }
    }

    protected boolean respondSmp(final String question, final String secret) {
        final Session session = mConversation.getOtrSession();
        if (session != null) {
            try {
                session.respondSmp(question, secret);
                return true;
            } catch (OtrException e) {
                return false;
            }
        } else {
            return false;
        }
    }

    protected boolean verifyWithUri(XmppUri uri) {
        Contact contact = mConversation.getContact();
        if (this.mConversation.getContact().getJid().equals(uri.getJid()) && uri.hasFingerprints()) {
            xmppConnectionService.verifyFingerprints(contact, uri.getFingerprints());
            Toast.makeText(this, R.string.verified, Toast.LENGTH_SHORT).show();
            updateView();
            return true;
        } else {
            Toast.makeText(this, R.string.could_not_verify_fingerprint, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    protected boolean isAccountOnline() {
        if (this.mAccount.getStatus() != Account.State.ONLINE) {
            Toast.makeText(this, R.string.not_connected_try_again, Toast.LENGTH_SHORT).show();
            return false;
        } else {
            return true;
        }
    }

    protected boolean handleIntent(Intent intent) {
        if (intent != null && intent.getAction().equals(ACTION_VERIFY_CONTACT)) {
            this.mAccount = extractAccount(intent);
            if (this.mAccount == null) {
                return false;
            }
            try {
                this.mConversation = this.xmppConnectionService.find(this.mAccount, Jid.of(intent.getExtras().getString("contact")));
                if (this.mConversation == null) {
                    return false;
                }
            } catch (final IllegalArgumentException ignored) {
                ignored.printStackTrace();
                return false;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            }
            this.mode = intent.getIntExtra("mode", MODE_MANUAL_VERIFICATION);
            // todo scan OTR fingerprint
            if (this.mode == MODE_SCAN_FINGERPRINT) {
                Log.d(Config.LOGTAG, "Scan OTR fingerprint is not implemented in this version");
                //new IntentIntegrator(this).initiateScan();
                return false;
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        // todo onActivityResult for OTR scan
        Log.d(Config.LOGTAG, "Scan OTR fingerprint result is not implemented in this version");
        /*if ((requestCode & 0xFFFF) == IntentIntegrator.REQUEST_CODE) {
            IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
            if (scanResult != null && scanResult.getFormatName() != null) {
                String data = scanResult.getContents();
                XmppUri uri = new XmppUri(data);
                if (xmppConnectionServiceBound) {
                    verifyWithUri(uri);
                    finish();
                } else {
                    this.mPendingUri = uri;
                }
            } else {
                finish();
            }
        }*/
        super.onActivityResult(requestCode, requestCode, intent);
    }

    @Override
    protected void onBackendConnected() {
        if (handleIntent(getIntent())) {
            updateView();
        } else if (mPendingUri != null) {
            verifyWithUri(mPendingUri);
            finish();
            mPendingUri = null;
        }
        setIntent(null);
    }

    protected void updateView() {
        if (this.mConversation != null && this.mConversation.hasValidOtrSession()) {
            final ActionBar actionBar = getSupportActionBar();
            this.mVerificationExplain.setText(R.string.no_otr_session_found);
            invalidateOptionsMenu();
            switch (this.mode) {
                case MODE_ASK_QUESTION:
                    if (actionBar != null) {
                        actionBar.setTitle(R.string.ask_question);
                    }
                    this.updateViewAskQuestion();
                    break;
                case MODE_ANSWER_QUESTION:
                    if (actionBar != null) {
                        actionBar.setTitle(R.string.smp_requested);
                    }
                    this.updateViewAnswerQuestion();
                    break;
                case MODE_MANUAL_VERIFICATION:
                default:
                    if (actionBar != null) {
                        actionBar.setTitle(R.string.manually_verify);
                    }
                    this.updateViewManualVerification();
                    break;
            }
        } else {
            this.mManualVerificationArea.setVisibility(View.GONE);
            this.mSmpVerificationArea.setVisibility(View.GONE);
        }
    }

    protected void updateViewManualVerification() {
        this.mVerificationExplain.setText(R.string.manual_verification_explanation);
        this.mManualVerificationArea.setVisibility(View.VISIBLE);
        this.mSmpVerificationArea.setVisibility(View.GONE);
        this.mYourFingerprint.setText(CryptoHelper.prettifyFingerprint(this.mAccount.getOtrFingerprint()));
        this.mRemoteFingerprint.setText(CryptoHelper.prettifyFingerprint(this.mConversation.getOtrFingerprint()));
        if (this.mConversation.isOtrFingerprintVerified()) {
            deactivateButton(this.mRightButton, R.string.verified);
            activateButton(this.mLeftButton, R.string.cancel, this.mFinishListener);
        } else {
            activateButton(this.mLeftButton, R.string.cancel, this.mFinishListener);
            activateButton(this.mRightButton, R.string.verify, new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showManuallyVerifyDialog();
                }
            });
        }
    }

    protected void updateViewAskQuestion() {
        this.mManualVerificationArea.setVisibility(View.GONE);
        this.mSmpVerificationArea.setVisibility(View.VISIBLE);
        this.mVerificationExplain.setText(R.string.smp_explain_question);
        final int smpStatus = this.mConversation.smp().status;
        switch (smpStatus) {
            case Conversation.Smp.STATUS_WE_REQUESTED:
                this.mStatusMessage.setVisibility(View.GONE);
                this.mSharedSecretHintEditable.setVisibility(View.VISIBLE);
                this.mSharedSecretSecret.setVisibility(View.VISIBLE);
                this.mSharedSecretHintEditable.setText(this.mConversation.smp().hint);
                this.mSharedSecretSecret.setText(this.mConversation.smp().secret);
                this.activateButton(this.mLeftButton, R.string.cancel, this.mCancelSharedSecretListener);
                this.deactivateButton(this.mRightButton, R.string.in_progress);
                break;
            case Conversation.Smp.STATUS_FAILED:
                this.mStatusMessage.setVisibility(View.GONE);
                this.mSharedSecretHintEditable.setVisibility(View.VISIBLE);
                this.mSharedSecretSecret.setVisibility(View.VISIBLE);
                this.mSharedSecretSecret.requestFocus();
                this.mSharedSecretSecret.setError(getString(R.string.secrets_do_not_match));
                this.deactivateButton(this.mLeftButton, R.string.cancel);
                this.activateButton(this.mRightButton, R.string.try_again, this.mRetrySharedSecretListener);
                break;
            case Conversation.Smp.STATUS_VERIFIED:
                this.mSharedSecretHintEditable.setText("");
                this.mSharedSecretHintEditable.setVisibility(View.GONE);
                this.mSharedSecretSecret.setText("");
                this.mSharedSecretSecret.setVisibility(View.GONE);
                this.mStatusMessage.setVisibility(View.VISIBLE);
                this.deactivateButton(this.mLeftButton, R.string.cancel);
                this.activateButton(this.mRightButton, R.string.finish, this.mFinishListener);
                break;
            default:
                this.mStatusMessage.setVisibility(View.GONE);
                this.mSharedSecretHintEditable.setVisibility(View.VISIBLE);
                this.mSharedSecretSecret.setVisibility(View.VISIBLE);
                this.activateButton(this.mLeftButton, R.string.cancel, this.mFinishListener);
                this.activateButton(this.mRightButton, R.string.ask_question, this.mCreateSharedSecretListener);
                break;
        }
    }

    protected void updateViewAnswerQuestion() {
        this.mManualVerificationArea.setVisibility(View.GONE);
        this.mSmpVerificationArea.setVisibility(View.VISIBLE);
        this.mVerificationExplain.setText(R.string.smp_explain_answer);
        this.mSharedSecretHintEditable.setVisibility(View.GONE);
        this.mSharedSecretHint.setVisibility(View.VISIBLE);
        this.deactivateButton(this.mLeftButton, R.string.cancel);
        final int smpStatus = this.mConversation.smp().status;
        switch (smpStatus) {
            case Conversation.Smp.STATUS_CONTACT_REQUESTED:
                this.mStatusMessage.setVisibility(View.GONE);
                this.mSharedSecretHint.setText(this.mConversation.smp().hint);
                this.activateButton(this.mRightButton, R.string.respond, this.mRespondSharedSecretListener);
                break;
            case Conversation.Smp.STATUS_VERIFIED:
                this.mSharedSecretHintEditable.setText("");
                this.mSharedSecretHintEditable.setVisibility(View.GONE);
                this.mSharedSecretHint.setVisibility(View.GONE);
                this.mSharedSecretSecret.setText("");
                this.mSharedSecretSecret.setVisibility(View.GONE);
                this.mStatusMessage.setVisibility(View.VISIBLE);
                this.activateButton(this.mRightButton, R.string.finish, this.mFinishListener);
                break;
            case Conversation.Smp.STATUS_FAILED:
            default:
                this.mSharedSecretSecret.requestFocus();
                this.mSharedSecretSecret.setError(getString(R.string.secrets_do_not_match));
                this.activateButton(this.mRightButton, R.string.finish, this.mFinishListener);
                break;
        }
    }

    protected void activateButton(Button button, int text, View.OnClickListener listener) {
        button.setEnabled(true);
        button.setText(text);
        button.setOnClickListener(listener);
    }

    protected void deactivateButton(Button button, int text) {
        button.setEnabled(false);
        button.setText(text);
        button.setOnClickListener(null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_verify_otr);
        this.mRemoteFingerprint = findViewById(R.id.remote_fingerprint);
        this.mYourFingerprint = findViewById(R.id.your_fingerprint);
        this.mLeftButton = findViewById(R.id.left_button);
        this.mRightButton = findViewById(R.id.right_button);
        this.mVerificationExplain = findViewById(R.id.verification_explanation);
        this.mStatusMessage = findViewById(R.id.status_message);
        this.mSharedSecretSecret = findViewById(R.id.shared_secret_secret);
        this.mSharedSecretHintEditable = findViewById(R.id.shared_secret_hint_editable);
        this.mSharedSecretHint = findViewById(R.id.shared_secret_hint);
        this.mManualVerificationArea = findViewById(R.id.manual_verification_area);
        this.mSmpVerificationArea = findViewById(R.id.smp_verification_area);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.verify_otr, menu);
        return true;
    }

    private void showManuallyVerifyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.manually_verify);
        builder.setMessage(R.string.are_you_sure_verify_fingerprint);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.verify, mVerifyFingerprintListener);
        builder.create().show();
    }

    @Override
    protected String getShareableUri() {
        if (mAccount != null) {
            return mAccount.getShareableUri();
        } else {
            return "";
        }
    }

    public void onConversationUpdate() {
        refreshUi();
    }

    @Override
    protected void refreshUiReal() {
        updateView();
    }
}
