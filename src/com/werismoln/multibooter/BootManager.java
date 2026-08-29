package com.werismoln.multibooter;

import com.werismoln.multibooter.R;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class BootManager extends Activity {

    /*
     * Boot mode IDs
     *
     * 0 = None
     * 1 = Ventoy
     * 2 = Direct USB Writer
     * 3 = USB Gadget
     * 4 = TFTP Server
     * 5 = FunctionFS
     */
    private static final int BOOT_NONE = 0;
    private static final int BOOT_VENTOY = 1;
    private static final int BOOT_USB_WRITER = 2;
    private static final int BOOT_GADGET = 3;
    private static final int BOOT_TFTP = 4;
    private static final int BOOT_FUNCTIONFS = 5;

    private static final String STATE_FILE = "restored-data";
    private static final String FLAG_ROOT_GRANTED = "root-granted=true";

    private static final String STATE_SELECTED_BOOT = "selected_boot";
    private static final String STATE_DRAWER_OPEN = "drawer_open";

    /*
     * Boot cards
     *
     * XML:
     * card_usb   -> Ventoy
     * card_usb2  -> Direct USB Writer
     * card_gadget
     * card_tftp
     * card_extra -> FunctionFS
     */
    private LinearLayout cardVentoy;
    private LinearLayout cardUsbWriter;
    private LinearLayout cardGadget;
    private LinearLayout cardTftp;
    private LinearLayout cardFunctionFs;

    private LinearLayout sideDrawer;
    private View drawerOverlay;

    private ImageView btnMenu;
    private Button btnContinue;

    private TextView menuHomepage;
    private TextView menuSettings;
    private TextView menuAbout;
    private TextView menuBuyMeCoffee;

    private boolean isDrawerOpen = false;
    private boolean isRootGranted = false;

    private int selectedBoot = BOOT_NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.boot_manager);

        bindViews();

        /*
         * Activity yeniden oluşturulduysa seçim ve drawer durumunu geri al.
         */
        if (savedInstanceState != null) {

            selectedBoot = savedInstanceState.getInt(
                STATE_SELECTED_BOOT,
                BOOT_NONE
            );

            isDrawerOpen = savedInstanceState.getBoolean(
                STATE_DRAWER_OPEN,
                false
            );
        }

        /*
         * MainActivity tarafından restored-data içine yazılan
         * root-granted=true satırını kontrol et.
         */
        isRootGranted = isRootGrantedSaved();

        configureBootCards();
        setupListeners();

        /*
         * Root yokken daha önce root isteyen bir kart seçilmiş olarak
         * restore edilirse geçersiz seçimi temizle.
         */
        if (
            !isRootGranted &&
            (
                selectedBoot == BOOT_GADGET ||
                selectedBoot == BOOT_TFTP ||
                selectedBoot == BOOT_FUNCTIONFS
            )
        ) {
            selectedBoot = BOOT_NONE;
        }

        restoreSelectedBoot();
        updateContinueButton();

        /*
         * Drawer durumunu ekran döndürme sonrası animasyonsuz geri yükle.
         */
        if (isDrawerOpen) {
            openDrawerImmediately();
        } else {
            closeDrawerImmediately();
        }
    }

    private void bindViews() {

        btnMenu =
            (ImageView) findViewById(R.id.btn_menu);

        btnContinue =
            (Button) findViewById(R.id.boot_continue);

        drawerOverlay =
            findViewById(R.id.drawer_overlay);

        sideDrawer =
            (LinearLayout) findViewById(R.id.side_drawer);

        /*
         * XML kart eşlemesi.
         */
        cardVentoy =
            (LinearLayout) findViewById(R.id.card_usb);

        cardUsbWriter =
            (LinearLayout) findViewById(R.id.card_usb2);

        cardGadget =
            (LinearLayout) findViewById(R.id.card_gadget);

        cardTftp =
            (LinearLayout) findViewById(R.id.card_tftp);

        cardFunctionFs =
            (LinearLayout) findViewById(R.id.card_extra);

        menuHomepage =
            (TextView) findViewById(R.id.menu_item_usb);

        menuSettings =
            (TextView) findViewById(R.id.menu_item_gadget);

        menuAbout =
            (TextView) findViewById(R.id.menu_item_tftp);

        menuBuyMeCoffee =
            (TextView) findViewById(R.id.menu_item_bmc);
    }

    private void configureBootCards() {

        /*
         * ROOT GEREKTİRMEYEN KARTLAR
         *
         * Ventoy ve Direct USB Writer her zaman aktiftir.
         */
        enableCard(cardVentoy);
        enableCard(cardUsbWriter);

        /*
         * ROOT GEREKTİREN KARTLAR
         *
         * USB Gadget
         * TFTP
         * FunctionFS
         *
         * Root yoksa üçü de pasif ve yarı saydam kalır.
         */
        if (isRootGranted) {

            enableCard(cardGadget);
            enableCard(cardTftp);
            enableCard(cardFunctionFs);

        } else {

            disableRootCard(cardGadget);
            disableRootCard(cardTftp);
            disableRootCard(cardFunctionFs);
        }

        /*
         * İlk görsel durum:
         * Seçili olmayan kartlar 0.97 ölçeğinde.
         */
        resetCardImmediately(cardVentoy);
        resetCardImmediately(cardUsbWriter);
        resetCardImmediately(cardGadget);
        resetCardImmediately(cardTftp);
        resetCardImmediately(cardFunctionFs);

        /*
         * Bütün kartlara aynı dokunma/seçim animasyonu.
         *
         * FunctionFS burada diğer kartlarla tamamen aynı davranışı alır.
         */
        addTouchAnimation(
            cardVentoy,
            BOOT_VENTOY
        );

        addTouchAnimation(
            cardUsbWriter,
            BOOT_USB_WRITER
        );

        addTouchAnimation(
            cardGadget,
            BOOT_GADGET
        );

        addTouchAnimation(
            cardTftp,
            BOOT_TFTP
        );

        addTouchAnimation(
            cardFunctionFs,
            BOOT_FUNCTIONFS
        );
    }

    private void enableCard(View view) {

        view.setEnabled(true);
        view.setClickable(true);
        view.setAlpha(1.0f);
    }

    private void disableRootCard(View view) {

        view.setEnabled(false);
        view.setClickable(false);
        view.setAlpha(0.5f);
    }

    private void setupListeners() {

        /*
         * Hamburger menu.
         */
        btnMenu.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {

                    if (isDrawerOpen) {
                        closeDrawer();
                    } else {
                        openDrawer();
                    }
                }
            }
        );

        /*
         * Drawer dışındaki karanlık alana basınca drawer kapanır.
         */
        drawerOverlay.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {
                    closeDrawer();
                }
            }
        );

        /*
         * Homepage zaten BootManager'ın bu ilk sayfasıdır.
         */
        menuHomepage.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {
                    closeDrawer();
                }
            }
        );

        /*
         *
         */
        menuSettings.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {

                    closeDrawer();

                    Toast.makeText(
                        BootManager.this,
                        "Settings page will be added later.",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            }
        );

        menuAbout.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {

                    closeDrawer();

                    Toast.makeText(
                        BootManager.this,
                        "About page will be added later.",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            }
        );

        menuBuyMeCoffee.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {

                    closeDrawer();

                    Toast.makeText(
                        BootManager.this,
                        "Buy Me a Coffee page will be added later.",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            }
        );

        /*
         * CONTINUE basma animasyonu.
         * XML'deki renk değiştirilmez.
         */
        btnContinue.setOnTouchListener(
            new View.OnTouchListener() {

                @Override
                public boolean onTouch(
                    View v,
                    MotionEvent event
                ) {

                    if (!v.isEnabled()) {
                        return false;
                    }

                    switch (event.getAction()) {

                        case MotionEvent.ACTION_DOWN:

                            v.animate().cancel();
                            v.setAlpha(0.4f);

                            break;


                        case MotionEvent.ACTION_UP:

                        case MotionEvent.ACTION_CANCEL:

                            v.animate()
                                .alpha(1.0f)
                                .setDuration(300)
                                .start();

                            break;
                    }

                    return false;
                }
            }
        );

        btnContinue.setOnClickListener(
            new View.OnClickListener() {

                @Override
                public void onClick(View v) {
                    continueWithSelectedBoot();
                }
            }
        );
    }

    /*
     * ------------------------------------------------------------
     * CARD TOUCH / SELECTION
     * ------------------------------------------------------------
     */

    private void addTouchAnimation(
        final View view,
        final int boot
    ) {

        view.setOnTouchListener(
            new View.OnTouchListener() {

                @Override
                public boolean onTouch(
                    View v,
                    MotionEvent event
                ) {

                    /*
                     * Root olmadığı için disabled olan Gadget/TFTP/
                     * FunctionFS kartları hiçbir seçim işlemi yapamaz.
                     */
                    if (!view.isEnabled()) {
                        return true;
                    }

                    switch (event.getAction()) {

                        case MotionEvent.ACTION_DOWN:

                            view.animate().cancel();

                            /*
                             * Parmağı basınca kart hafif küçülür.
                             */
                            view.animate()
                                .scaleX(0.95f)
                                .scaleY(0.95f)
                                .setDuration(70)
                                .start();

                            return true;


                        case MotionEvent.ACTION_MOVE:

                            /*
                             * MOVE sırasında seçim yapılmaz.
                             * ACTION_UP sırasında parmak hâlâ kartın
                             * içindeyse seçim yapılır.
                             */
                            return true;


                        case MotionEvent.ACTION_UP:

                            boolean isInside =
                                event.getX() >= 0 &&
                                event.getX() <= view.getWidth() &&
                                event.getY() >= 0 &&
                                event.getY() <= view.getHeight();

                            if (isInside) {

                                selectBoot(boot);

                            } else {

                                restoreCardScale(
                                    view,
                                    boot
                                );
                            }

                            return true;


                        case MotionEvent.ACTION_CANCEL:

                            restoreCardScale(
                                view,
                                boot
                            );

                            return true;
                    }

                    return true;
                }
            }
        );
    }

    private void selectBoot(int boot) {

        /*
         * İkinci root koruması.
         *
         * View disabled olsa bile programatik olarak yanlışlıkla
         * çağrılırsa Gadget/TFTP/FunctionFS seçilemesin.
         */
        if (
            (
                boot == BOOT_GADGET ||
                boot == BOOT_TFTP ||
                boot == BOOT_FUNCTIONFS
            ) &&
            !isRootGranted
        ) {
            return;
        }

        selectedBoot = boot;

        /*
         * Önce bütün kartları seçilmemiş duruma getir.
         */
        resetCard(cardVentoy);
        resetCard(cardUsbWriter);
        resetCard(cardGadget);
        resetCard(cardTftp);
        resetCard(cardFunctionFs);

        /*
         * Seçilen kartı 1.00 ölçeğine getir.
         */
        View selectedCard =
            getCardForBoot(boot);

        if (selectedCard != null) {

            selectedCard.setSelected(true);
            scaleSelected(selectedCard);
        }

        updateContinueButton();
    }

    private View getCardForBoot(int boot) {

        switch (boot) {

            case BOOT_VENTOY:
                return cardVentoy;


            case BOOT_USB_WRITER:
                return cardUsbWriter;


            case BOOT_GADGET:
                return cardGadget;


            case BOOT_TFTP:
                return cardTftp;


            case BOOT_FUNCTIONFS:
                return cardFunctionFs;


            default:
                return null;
        }
    }

    private void resetCard(View view) {

        view.setSelected(false);
        view.animate().cancel();

        view.animate()
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(100)
            .start();
    }

    private void resetCardImmediately(View view) {

        view.animate().cancel();

        view.setSelected(false);
        view.setScaleX(0.97f);
        view.setScaleY(0.97f);
    }

    private void scaleSelected(View view) {

        view.animate().cancel();

        view.animate()
            .scaleX(1.00f)
            .scaleY(1.00f)
            .setDuration(100)
            .start();
    }

    private void scaleSelectedImmediately(View view) {

        view.animate().cancel();

        view.setScaleX(1.00f);
        view.setScaleY(1.00f);
    }

    private void restoreCardScale(
        View view,
        int boot
    ) {

        view.animate().cancel();

        /*
         * Parmağı kart dışında bıraktığımızda:
         *
         * Kart zaten seçiliyse -> 1.00
         * Seçili değilse      -> 0.97
         */
        if (selectedBoot == boot) {

            view.animate()
                .scaleX(1.00f)
                .scaleY(1.00f)
                .setDuration(100)
                .start();

        } else {

            view.animate()
                .scaleX(0.97f)
                .scaleY(0.97f)
                .setDuration(100)
                .start();
        }
    }

    private void restoreSelectedBoot() {

        resetCardImmediately(cardVentoy);
        resetCardImmediately(cardUsbWriter);
        resetCardImmediately(cardGadget);
        resetCardImmediately(cardTftp);
        resetCardImmediately(cardFunctionFs);

        View selectedCard =
            getCardForBoot(selectedBoot);

        if (selectedCard == null) {

            selectedBoot = BOOT_NONE;
            return;
        }

        /*
         * Root olmadığı için disabled olan bir kart restore edilmesin.
         */
        if (!selectedCard.isEnabled()) {

            selectedBoot = BOOT_NONE;
            return;
        }

        selectedCard.setSelected(true);
        scaleSelectedImmediately(selectedCard);
    }

    /*
     * ------------------------------------------------------------
     * CONTINUE
     * ------------------------------------------------------------
     */

    private void updateContinueButton() {

        boolean hasSelection =
            selectedBoot != BOOT_NONE;

        btnContinue.setEnabled(hasSelection);

        /*
         * Renk değiştirilmez.
         * XML'deki #2563EB aynen kalır.
         */
        btnContinue.setAlpha(1.0f);
    }

    private void continueWithSelectedBoot() {

        /*
         * Sonraki sayfalar hazır olduğunda sadece bu switch içindeki
         * ilgili case'e Intent eklemek yeterli olacak.
         */
        switch (selectedBoot) {

            case BOOT_VENTOY:

                Toast.makeText(
                    BootManager.this,
                    "Ventoy mode selected.",
                    Toast.LENGTH_SHORT
                ).show();

                break;


            case BOOT_USB_WRITER:

                Toast.makeText(
                    BootManager.this,
                    "USB writer mode selected.",
                    Toast.LENGTH_SHORT
                ).show();

                break;


            case BOOT_GADGET:

                Toast.makeText(
                    BootManager.this,
                    "USB Gadget mode selected.",
                    Toast.LENGTH_SHORT
                ).show();

                break;


            case BOOT_TFTP:

                Toast.makeText(
                    BootManager.this,
                    "TFTP server mode selected.",
                    Toast.LENGTH_SHORT
                ).show();

                break;


            case BOOT_FUNCTIONFS:

                Toast.makeText(
                    BootManager.this,
                    "FunctionFS mode selected.",
                    Toast.LENGTH_SHORT
                ).show();

                break;


            default:

                break;
        }
    }

    /*
     * ------------------------------------------------------------
     * ROOT STATE
     * ------------------------------------------------------------
     */

    private boolean isRootGrantedSaved() {

        File file =
            new File(
                getFilesDir(),
                STATE_FILE
            );

        if (!file.exists()) {
            return false;
        }

        BufferedReader br = null;

        try {

            br = new BufferedReader(
                new FileReader(file)
            );

            String line;

            while ((line = br.readLine()) != null) {

                if (
                    FLAG_ROOT_GRANTED.equals(
                        line.trim()
                    )
                ) {
                    return true;
                }
            }

        } catch (Exception e) {

            e.printStackTrace();

        } finally {

            try {

                if (br != null) {
                    br.close();
                }

            } catch (Exception ignored) {
            }
        }

        return false;
    }

    /*
     * ------------------------------------------------------------
     * DRAWER
     * ------------------------------------------------------------
     */

    private float getDrawerWidth() {

        /*
         * Drawer görünürse gerçek ölçüsü.
         */
        if (sideDrawer.getWidth() > 0) {
            return sideDrawer.getWidth();
        }

        /*
         * İlk açılışta View henüz ölçülmemiş olabilir.
         * XML'deki 260dp değeri LayoutParams içinde pixel olarak bulunur.
         */
        if (
            sideDrawer.getLayoutParams() != null &&
            sideDrawer.getLayoutParams().width > 0
        ) {
            return sideDrawer.getLayoutParams().width;
        }

        return 0.0f;
    }

    private void openDrawer() {

        sideDrawer.animate().cancel();
        drawerOverlay.animate().cancel();

        float drawerWidth =
            getDrawerWidth();

        /*
         * Drawer önce ekranın soluna yerleştirilir.
         */
        sideDrawer.setTranslationX(
            -drawerWidth
        );

        drawerOverlay.setAlpha(0.0f);

        sideDrawer.setVisibility(
            View.VISIBLE
        );

        drawerOverlay.setVisibility(
            View.VISIBLE
        );

        isDrawerOpen = true;

        /*
         * Soldan içeri kaydır.
         */
        sideDrawer.animate()
            .translationX(0.0f)
            .setDuration(300)
            .start();

        /*
         * Overlay'i görünür hale getir.
         */
        drawerOverlay.animate()
            .alpha(1.0f)
            .setDuration(300)
            .start();
    }

    private void openDrawerImmediately() {

        sideDrawer.animate().cancel();
        drawerOverlay.animate().cancel();

        sideDrawer.setVisibility(
            View.VISIBLE
        );

        drawerOverlay.setVisibility(
            View.VISIBLE
        );

        sideDrawer.setTranslationX(0.0f);
        drawerOverlay.setAlpha(1.0f);

        isDrawerOpen = true;
    }

    private void closeDrawer() {

        if (!isDrawerOpen) {
            return;
        }

        sideDrawer.animate().cancel();
        drawerOverlay.animate().cancel();

        isDrawerOpen = false;

        final float drawerWidth =
            getDrawerWidth();

        /*
         * Drawer'ı sola çıkar.
         */
        sideDrawer.animate()
            .translationX(-drawerWidth)
            .setDuration(250)
            .start();

        /*
         * Overlay'i söndür.
         */
        drawerOverlay.animate()
            .alpha(0.0f)
            .setDuration(250)
            .setListener(
                new AnimatorListenerAdapter() {

                    @Override
                    public void onAnimationEnd(
                        Animator animation
                    ) {

                        if (!isDrawerOpen) {

                            sideDrawer.setVisibility(
                                View.GONE
                            );

                            drawerOverlay.setVisibility(
                                View.GONE
                            );
                        }

                        /*
                         * Bir sonraki animasyona listener taşınmasın.
                         */
                        drawerOverlay
                            .animate()
                            .setListener(null);
                    }
                }
            )
            .start();
    }

    private void closeDrawerImmediately() {

        sideDrawer.animate().cancel();
        drawerOverlay.animate().cancel();

        sideDrawer.setTranslationX(
            -getDrawerWidth()
        );

        drawerOverlay.setAlpha(0.0f);

        sideDrawer.setVisibility(
            View.GONE
        );

        drawerOverlay.setVisibility(
            View.GONE
        );

        isDrawerOpen = false;
    }

    /*
     * ------------------------------------------------------------
     * ACTIVITY STATE
     * ------------------------------------------------------------
     */

    @Override
    public void onBackPressed() {

        if (isDrawerOpen) {

            closeDrawer();

        } else {

            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(
        Bundle outState
    ) {

        super.onSaveInstanceState(outState);

        outState.putInt(
            STATE_SELECTED_BOOT,
            selectedBoot
        );

        outState.putBoolean(
            STATE_DRAWER_OPEN,
            isDrawerOpen
        );
    }
}
