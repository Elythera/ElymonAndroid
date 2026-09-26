package com.kdt.mcgui;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.NetworkInfo;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.core.content.res.ResourcesCompat;


import com.elythera.elymon.auth.AuthorizationGrant;
import com.elythera.elymon.auth.ElymonAccounts;
import com.elythera.elymon.auth.ElymonAuthException;
import com.elythera.elymon.auth.ElymonSession;
import com.elythera.elymon.auth.MicrosoftAuthFailure;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.listener.ProgressListener;
import net.kdt.pojavlaunch.authenticator.microsoft.PresentedException;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import fr.spse.extended_view.ExtendedTextView;

public class mcAccountSpinner extends AppCompatSpinner implements AdapterView.OnItemSelectedListener {
    public mcAccountSpinner(@NonNull Context context) {
        this(context, null);
    }
    public mcAccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private final List<String> mAccountList = new ArrayList<>(2);
    private MinecraftAccount mSelectecAccount = null;

    /* Display the head of the current profile, here just to allow bitmap recycling */
    private BitmapDrawable mHeadDrawable;

    /* Current animator to for the login bar, is swapped when changing step */
    private ObjectAnimator mLoginBarAnimator;
    private float mLoginBarWidth = -1;

    /* Paint used to display the bottom bar, to show the login progress. */
    private final Paint mLoginBarPaint = new Paint();

    /* When a login is performed in the background, we need to know where we are */
    private final static int MAX_LOGIN_STEP = 5;
    private int mLoginStep = 0;

    /* Login listeners */
    private final ProgressListener mProgressListener = step -> {
        // Animate the login bar, cosmetic purposes only
        mLoginStep = step;
        if(mLoginBarAnimator != null){
            mLoginBarAnimator.cancel();
            mLoginBarAnimator.setFloatValues( mLoginBarWidth, (getWidth()/MAX_LOGIN_STEP * mLoginStep));
        }else{
            mLoginBarAnimator = ObjectAnimator.ofFloat(this, "LoginBarWidth", mLoginBarWidth, (getWidth()/MAX_LOGIN_STEP * mLoginStep));
        }
        mLoginBarAnimator.start();
    };

    private final DoneListener mDoneListener = account -> {
        Toast.makeText(getContext(), R.string.elymon_auth_login_done, Toast.LENGTH_SHORT).show();

        // ELYMON: always reload from disk and select the account. Upstream returned early for an
        // account already listed, which kept the old account and token in memory, and a refresh
        // may have moved the account to a new Minecraft name (ElymonAccounts.forgetOldName).
        mSelectecAccount = account;
        invalidate();
        loadAccountList();
        int position = mAccountList.indexOf(account.username);
        reloadAccounts(false, Math.max(position, 0));
    };

    private final ErrorListener mErrorListener = errorMessage -> {
        if(isScreenGone()) return; // ELYMON: see isScreenGone()
        mLoginBarPaint.setColor(Color.RED);
        Context context = getContext();
        // ELYMON: a lost session (expired or revoked refresh token) offers to sign in again.
        if(errorMessage instanceof MicrosoftAuthFailure
                && ((MicrosoftAuthFailure) errorMessage).getKind() == ElymonAuthException.Kind.RECONNECT) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.elymon_auth_error_title)
                    .setMessage(((MicrosoftAuthFailure) errorMessage).toString(context))
                    .setPositiveButton(R.string.elymon_auth_reconnect,
                            (dialog, which) -> ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            invalidate();
            return;
        }
        if(errorMessage instanceof PresentedException) {
            PresentedException exception = (PresentedException) errorMessage;
            Throwable cause = exception.getCause();
            if(cause == null) {
                // ELYMON: French title (elymon_auth_strings.xml).
                Tools.dialog(context, context.getString(R.string.elymon_auth_error_title), exception.toString(context));
            }else {
                Tools.showError(context, exception.toString(context), exception.getCause());
            }
        }else {
            Tools.showError(getContext(), errorMessage);
        }
        invalidate();
    };

    /* Triggered when we need to do microsoft login */
    // ELYMON: MicrosoftLoginFragment posts the authorization code with its PKCE verifier. The
    // value leaves the bus at once, and only the spinner on screen redeems it, once: a spinner
    // left behind by a destroyed activity may still be listening.
    private final ExtraListener<Object> mMicrosoftLoginListener = (key, value) -> {
        if(!(value instanceof AuthorizationGrant) || !isAttachedToWindow()) return false;
        ExtraCore.removeValue(ExtraConstants.MICROSOFT_LOGIN_TODO);
        AuthorizationGrant grant = (AuthorizationGrant) value;
        if(!grant.claim()) return false;
        mLoginBarPaint.setColor(getResources().getColor(R.color.minebutton_color));
        new MicrosoftBackgroundLogin(grant.code, grant.codeVerifier).performLogin(
                mProgressListener, mDoneListener, mErrorListener);
        return false;
    };

    // ELYMON: the local ("mojang") login listener is gone, Elymon only takes Microsoft accounts.


    @SuppressLint("ClickableViewAccessibility")
    private void init(){
        // Set visual properties
        setBackgroundColor(getResources().getColor(R.color.background_status_bar));
        mLoginBarPaint.setColor(getResources().getColor(R.color.minebutton_color));
        mLoginBarPaint.setStrokeWidth(getResources().getDimensionPixelOffset(R.dimen._2sdp));

        // ELYMON: context for the French messages of the refresh before Play (ElymonSession.ensureFresh).
        ElymonSession.attach(getContext());

        // Set behavior
        reloadAccounts(true, 0);
        setOnItemSelectedListener(this);

        ExtraCore.addExtraListener(ExtraConstants.MICROSOFT_LOGIN_TODO, mMicrosoftLoginListener);
    }


    @Override
    public final void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if(position == 0){  // Add account button
            if(mAccountList.size() > 1){
                ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            }
            return;
        }

        pickAccount(position);
        if(mSelectecAccount != null)
            performLogin(mSelectecAccount);
    }

    @Override
    public final void onNothingSelected(AdapterView<?> parent) {}


    @Override
    protected void onDraw(Canvas canvas) {
        if(mLoginBarWidth == -1) mLoginBarWidth = getWidth(); // Initial draw

        float bottom = getHeight() - mLoginBarPaint.getStrokeWidth()/2;
        canvas.drawLine(0, bottom, mLoginBarWidth, bottom, mLoginBarPaint);
    }

    public void removeCurrentAccount(){
        removeAccount(getSelectedItemPosition());
    }

    private void removeAccount(int position) {
        if(position == 0) return;
        String name = mAccountList.get(position); // ELYMON
        File accountFile = new File(Tools.DIR_ACCOUNT_NEW, name+".json");
        if(accountFile.exists()) accountFile.delete();
        // ELYMON: also forget the cached head and, if it was selected, the selection.
        ElymonAccounts.forgetHead(name);
        if(name.equals(PojavProfile.getCurrentProfileName(getContext()))) PojavProfile.setCurrentProfile(getContext(), null);
        mAccountList.remove(position);

        reloadAccounts(false, 0);
    }

    @Keep
    public void setLoginBarWidth(float value){
        mLoginBarWidth = value;
        invalidate(); // Need to redraw each time this is changed
    }

    /** Allows checking whether we have an online account */
    public boolean isAccountOnline(){
        return mSelectecAccount != null && !mSelectecAccount.accessToken.equals("0");
    }

    public MinecraftAccount getSelectedAccount(){
        return mSelectecAccount;
    }

    public int getLoginState(){
        return mLoginStep;
    }

    public boolean isLoginDone(){
        return mLoginStep >= MAX_LOGIN_STEP;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setNoAccountBehavior(){
        // Set custom behavior when no account are present, to make it act as a button
        if(mAccountList.size() != 1){
            // Remove any touch listener
            setOnTouchListener(null);
            return;
        }

        // Make the spinner act like a button, since there is no item to really select
        setOnTouchListener((v, event) -> {
            if(event.getAction() != MotionEvent.ACTION_UP) return false;
            // The activity should intercept this and spawn another fragment
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return true;
        });
    }

    /**
     * Reload the spinner, from memory or from scratch. A default account can be selected
     * @param fromFiles Whether we use files as the source of truth
     * @param overridePosition Force the spinner to be at this position, if not 0
     */
    private void reloadAccounts(boolean fromFiles, int overridePosition){
        if(fromFiles){
            loadAccountList(); // ELYMON
        }

        String[] accountArray = mAccountList.toArray(new String[0]);
        AccountAdapter accountAdapter = new AccountAdapter(getContext(), R.layout.item_minecraft_account, accountArray);
        accountAdapter.setDropDownViewResource(R.layout.item_minecraft_account);
        setAdapter(accountAdapter);

        // Pick what's available, might just be the the add account "button"
        pickAccount(overridePosition == 0 ? -1 : overridePosition);
        if(mSelectecAccount != null)
            performLogin(mSelectecAccount);

        // Remove or add the behavior if needed
        setNoAccountBehavior();

    }

    // ELYMON: a login can end after the screen that started it was destroyed (rotation, dark
    // mode): a dialog on that screen would crash (BadTokenException). MicrosoftBackgroundLogin
    // has already logged the failure, and the stored account is left as it was.
    private boolean isScreenGone(){
        Context context = getContext();
        while(context instanceof ContextWrapper && !(context instanceof Activity)){
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity
                && (((Activity) context).isFinishing() || ((Activity) context).isDestroyed());
    }

    // ELYMON: the file listing of reloadAccounts, with a French label and only Microsoft accounts.
    private void loadAccountList(){
        mAccountList.clear();

        mAccountList.add(getContext().getString(R.string.elymon_auth_add_account));
        mAccountList.addAll(ElymonAccounts.listMicrosoftAccountNames());
    }

    private void performLogin(MinecraftAccount minecraftAccount){
        // Logging in when there's no internet is useless. This should really be turned into a network callback though.
        if(!Tools.isOnline(getContext())){
            return;
        }
        if(minecraftAccount.isLocal()) return;

        mLoginBarPaint.setColor(getResources().getColor(R.color.minebutton_color));
        if(minecraftAccount.isMicrosoft){
            if(System.currentTimeMillis() > minecraftAccount.expiresAt){
                // Perform login only if needed
                // ELYMON: through the Elythera Entra app; the refresh re-reads the account under
                // the refresh lock. The refresh right before Play is ElymonSession.ensureFresh.
                MicrosoftBackgroundLogin.refreshing(minecraftAccount)
                        .performLogin(mProgressListener, mDoneListener, mErrorListener);
            }
            return;
        }
    }

    /** Pick the selected account, the one in settings if 0 is passed */
    private void pickAccount(int position){
        MinecraftAccount selectedAccount;
        if(position != -1){
            PojavProfile.setCurrentProfile(getContext(), mAccountList.get(position));
            selectedAccount = PojavProfile.getCurrentProfileContent(getContext(), mAccountList.get(position));

            // WORKAROUND
            // Account file corrupted due to previous versions having improper encoding
            if (selectedAccount == null){
                Context ctx = Objects.requireNonNull(getContext());

                new AlertDialog.Builder(ctx)
                        .setCancelable(false)
                        .setTitle(R.string.account_corrupted)
                        .setMessage(R.string.login_again)
                        .setPositiveButton(R.string.delete_account_and_login, (dialog, which) -> {
                            removeCurrentAccount();
                            pickAccount(-1);
                            setSelection(0);
                        })
                        .show();


            }
            setSelection(position);
        }else {
            // Get the current profile, or the first available profile if the wanted one is unavailable
            selectedAccount = PojavProfile.getCurrentProfileContent(getContext(), null);
            // ELYMON: an account left out of the list (not Microsoft) is never selected behind the list's back.
            if(selectedAccount != null && !mAccountList.contains(selectedAccount.username)) selectedAccount = null;
            int spinnerPosition = selectedAccount == null
                    ? mAccountList.size() <= 1 ? 0 : 1
                    : mAccountList.indexOf(selectedAccount.username);
            setSelection(spinnerPosition, false);
        }

        mSelectecAccount = selectedAccount;
        setImageFromSelectedAccount();
    }

    @Deprecated()
    /* Legacy behavior, update the head image manually for the selected account */
    private void setImageFromSelectedAccount(){
        BitmapDrawable oldBitmapDrawable = mHeadDrawable;

        if(mSelectecAccount != null){
            View layout = getSelectedView();
            if(layout != null){
                ExtendedTextView view = layout.findViewById(R.id.account_item);
                Bitmap bitmap = mSelectecAccount.getSkinFace();
                if(bitmap != null) {
                    mHeadDrawable = new BitmapDrawable(getResources(), bitmap);
                    view.setCompoundDrawables(mHeadDrawable, null, null, null);
                }else{
                    view.setCompoundDrawables(null, null, null, null);
                }
                view.postProcessDrawables();
            }
        }

        if(oldBitmapDrawable != null){
            oldBitmapDrawable.getBitmap().recycle();
        }
    }

    private class AccountAdapter extends ArrayAdapter<String> {

        private final HashMap<String, Drawable> mImageCache = new HashMap<>();
        public AccountAdapter(@NonNull Context context, int resource, @NonNull String[] objects) {
            super(context, resource, objects);
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if(convertView == null){
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_minecraft_account, parent, false);
            }

            ExtendedTextView textview = convertView.findViewById(R.id.account_item);
            ImageView deleteButton = convertView.findViewById(R.id.delete_account_button);
            textview.setText(super.getItem(position));

            // Handle the "Add account section"
            if(position == 0) {
                textview.setCompoundDrawables(ResourcesCompat.getDrawable(parent.getResources(), R.drawable.ic_add, null), null, null, null);
                deleteButton.setVisibility(View.GONE);
            }
            else {
                String username = super.getItem(position);
                Drawable accountHead = mImageCache.get(username);
                if (accountHead == null){
                    accountHead = new BitmapDrawable(parent.getResources(), MinecraftAccount.getSkinFace(username));
                    mImageCache.put(username, accountHead);
                }
                textview.setCompoundDrawables(accountHead, null, null, null);

                deleteButton.setVisibility(View.VISIBLE);
                deleteButton.setOnClickListener(v -> {
                    showDeleteDialog(getContext(), position);
                });
            }
            return convertView;
        }



        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = getDropDownView(position, convertView, parent);
            view.findViewById(R.id.delete_account_button).setVisibility(View.GONE);
            return view;
        }

        private void showDeleteDialog(Context context, int position) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.warning_remove_account)
                    .setPositiveButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.global_delete, (dialog, which) -> {
                        onDetachedFromWindow();
                        removeAccount(position);
                    })
                    .show();
        }
    }



}
