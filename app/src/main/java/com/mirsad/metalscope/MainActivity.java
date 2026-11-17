package com.mirsad.metalscope;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.mirsad.metalscope.core.SignalProcessor;
import com.mirsad.metalscope.ui.FieldGaugeView;
import com.mirsad.metalscope.ui.SignalChartView;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline Arabic-first Android interface for the device's actual 3-axis magnetometer.
 * There is intentionally no simulated reading, network request, location access or camera use.
 */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final int REQUEST_EXPORT_CSV = 41;
    private static final long CHART_INTERVAL_NS = 100_000_000L;
    private static final int MAX_SESSION_ROWS = 12_000;

    private static final int BG = 0xFF071219;
    private static final int SURFACE = 0xFF101F29;
    private static final int SURFACE_DARK = 0xFF0B1820;
    private static final int BORDER = 0xFF263A45;
    private static final int PRIMARY = 0xFFF4FAFB;
    private static final int SECONDARY = 0xFF9FB2BA;
    private static final int TEAL = 0xFF58E6C5;
    private static final int AMBER = 0xFFFFC56D;
    private static final int RED = 0xFFFF777B;
    private static final double MIN_SENSITIVITY = 2.0;
    private static final double MAX_SENSITIVITY = 30.0;

    private final SignalProcessor processor = new SignalProcessor();
    private final List<String> sessionRows = new ArrayList<>();

    private SensorManager sensorManager;
    private Sensor magneticSensor;
    private boolean usingUncalibratedSensor;
    private boolean sensorRegistered;
    private boolean soundEnabled;
    private boolean vibrationEnabled;
    private boolean calibrationFailureSticky;
    private int sensorAccuracy = -1;
    private long lastUiUpdateMs;
    private long lastChartSampleNs;
    private long sessionStartedNs;
    private SignalProcessor.Reading latestReading;
    private SharedPreferences preferences;
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;

    private TextView sensorBadge;
    private TextView stateTitle;
    private TextView stateMessage;
    private TextView fieldValue;
    private TextView baselineValue;
    private TextView noiseValue;
    private TextView thresholdValue;
    private TextView calibrationText;
    private TextView sensorInfo;
    private TextView axisXValue;
    private TextView axisYValue;
    private TextView axisZValue;
    private TextView actionButton;
    private TextView recalibrateButton;
    private TextView soundButton;
    private TextView vibrationButton;
    private TextView exportButton;
    private TextView exportHint;
    private ProgressBar calibrationProgress;
    private SeekBar sensitivitySeekBar;
    private FieldGaugeView gaugeView;
    private SignalChartView chartView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();

        preferences = getSharedPreferences("mirsad_settings", MODE_PRIVATE);
        soundEnabled = preferences.getBoolean("sound_enabled", false);
        vibrationEnabled = preferences.getBoolean("vibration_enabled", false);
        processor.setSensitivity(preferences.getFloat("sensitivity_ut", 6.0f));

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        magneticSensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (magneticSensor == null && sensorManager != null) {
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
            usingUncalibratedSensor = magneticSensor != null;
        }
        if (magneticSensor != null) {
            processor.setSensorCharacteristics(magneticSensor.getResolution(), magneticSensor.getMaximumRange());
        }
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        buildInterface();
        render(processor.currentReading(SystemClock.elapsedRealtimeNanos()));
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Draw behind system bars on Android 11+ and apply their insets to the content below.
            getWindow().setDecorFitsSystemWindows(false);
        }
    }

    private void buildInterface() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(dp(18), dp(14), dp(18), dp(28));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scrollView.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safeInsets = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                page.setPadding(dp(18) + safeInsets.left,
                        dp(14) + safeInsets.top,
                        dp(18) + safeInsets.right,
                        dp(28) + safeInsets.bottom);
                return insets;
            });
        }
        scrollView.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);

        buildHeader(page);
        addSpace(page, 16);
        buildStatusCard(page);
        addSpace(page, 14);
        buildGaugeCard(page);
        addSpace(page, 14);
        buildActionCard(page);
        addSpace(page, 14);
        buildSensitivityCard(page);
        addSpace(page, 14);
        buildChartCard(page);
        addSpace(page, 14);
        buildAxisCard(page);
        addSpace(page, 14);
        buildAlertsCard(page);
        addSpace(page, 14);
        buildSessionCard(page);
        addSpace(page, 14);
        buildLimitsCard(page);
        addSpace(page, 14);
        buildCreditsCard(page);

        TextView footer = makeText("قراءة فعلية من مستشعر الهاتف  ·  تعمل دون إنترنت أو حسابات",
                10, SECONDARY, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(4), dp(12), dp(4), dp(4));
        page.addView(footer, matchWrap());
    }

    private void buildHeader(LinearLayout parent) {
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);

        BrandMarkView mark = new BrandMarkView(this);
        header.addView(mark, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout titleGroup = vertical();
        titleGroup.setPadding(dp(12), 0, dp(6), 0);
        TextView title = makeText("مِرْصاد", 22, PRIMARY, true);
        TextView subtitle = makeText("كاشف المجال المغناطيسي", 12, SECONDARY, false);
        titleGroup.addView(title, wrapWrap());
        titleGroup.addView(subtitle, wrapWrap());
        header.addView(titleGroup, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sensorBadge = makeBadge("جارٍ التحقق");
        header.addView(sensorBadge, wrapWrap());
        parent.addView(header, matchWrap());
    }

    private void buildStatusCard(LinearLayout parent) {
        LinearLayout card = card();
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF142D36, 0xFF10212B});
        background.setCornerRadius(dp(22));
        background.setStroke(dp(1), 0xFF31505A);
        card.setBackground(background);

        TextView eyebrow = makeText("مختبر المجال  ·  قراءة حيّة", 11, TEAL, true);
        stateTitle = makeText("جارٍ تشغيل مستشعر الهاتف", 19, PRIMARY, true);
        stateMessage = makeText("تُعرض بيانات الحساس الحقيقي فقط؛ لا توجد قراءات تجريبية.",
                13, SECONDARY, false);
        stateMessage.setLineSpacing(dp(2), 1f);

        card.addView(eyebrow, matchWrap());
        addSpace(card, 9);
        card.addView(stateTitle, matchWrap());
        addSpace(card, 5);
        card.addView(stateMessage, matchWrap());
        parent.addView(card, matchWrap());
    }

    private void buildGaugeCard(LinearLayout parent) {
        LinearLayout card = card();
        addSectionHeader(card, "انحراف المجال", "مقاس بالميكروتسلا");

        gaugeView = new FieldGaugeView(this);
        LinearLayout.LayoutParams gaugeParams = matchWrap();
        gaugeParams.height = dp(238);
        gaugeView.setReading(latestReading);
        card.addView(gaugeView, gaugeParams);

        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams dividerParams = matchWrap();
        dividerParams.height = dp(1);
        dividerParams.topMargin = dp(2);
        dividerParams.bottomMargin = dp(12);
        card.addView(divider, dividerParams);

        LinearLayout metrics = horizontal();
        metrics.setGravity(Gravity.CENTER_VERTICAL);
        fieldValue = makeMetric(metrics, "المجال الحالي", "— µT");
        baselineValue = makeMetric(metrics, "خط الأساس", "— µT");
        noiseValue = makeMetric(metrics, "أرضية الضجيج", "— µT");
        card.addView(metrics, matchWrap());
        parent.addView(card, matchWrap());
    }

    private TextView makeMetric(LinearLayout parent, String caption, String value) {
        LinearLayout tile = vertical();
        tile.setGravity(Gravity.CENTER);
        TextView number = makeText(value, 15, PRIMARY, true);
        number.setGravity(Gravity.CENTER);
        TextView label = makeText(caption, 10, SECONDARY, false);
        label.setGravity(Gravity.CENTER);
        tile.addView(number, matchWrap());
        LinearLayout.LayoutParams labelParams = matchWrap();
        labelParams.topMargin = dp(4);
        tile.addView(label, labelParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        parent.addView(tile, params);
        // Return the number view so the caller can update it without a second lookup.
        if (caption.equals("المجال الحالي")) {
            fieldValue = number;
        } else if (caption.equals("خط الأساس")) {
            baselineValue = number;
        } else {
            noiseValue = number;
        }
        return number;
    }

    private void buildActionCard(LinearLayout parent) {
        LinearLayout card = card();
        addSectionHeader(card, "طريقة القياس", "معايرة محلية");
        TextView instructions = makeText(
                "أبعد الهاتف عن المغانط والحديد والشواحن، وضعه ثابتًا. نأخذ مرجعًا للبيئة الحالية قبل بدء المسح.",
                12, SECONDARY, false);
        instructions.setLineSpacing(dp(2), 1f);
        card.addView(instructions, matchWrap());
        addSpace(card, 13);

        actionButton = makeButton("معايرة خط الأساس", true);
        actionButton.setOnClickListener(view -> onMainAction());
        card.addView(actionButton, matchWrap());

        calibrationProgress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        calibrationProgress.setMax(100);
        calibrationProgress.setProgressTintList(ColorStateList.valueOf(TEAL));
        calibrationProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF263A45));
        calibrationProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(10);
        card.addView(calibrationProgress, progressParams);

        calibrationText = makeText("المعايرة تثبّت المجال المحيط ولا تزيد قدرة المستشعر نفسه.",
                11, SECONDARY, false);
        calibrationText.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams calibrationTextParams = matchWrap();
        calibrationTextParams.topMargin = dp(9);
        card.addView(calibrationText, calibrationTextParams);

        recalibrateButton = makeButton("إعادة معايرة المكان", false);
        recalibrateButton.setOnClickListener(view -> beginCalibration());
        LinearLayout.LayoutParams recParams = matchWrap();
        recParams.topMargin = dp(10);
        card.addView(recalibrateButton, recParams);
        parent.addView(card, matchWrap());
    }

    private void buildSensitivityCard(LinearLayout parent) {
        LinearLayout card = card();
        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = makeText("حساسية التنبيه", 15, PRIMARY, true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        thresholdValue = makeText("6.0 µT", 14, TEAL, true);
        thresholdValue.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        header.addView(thresholdValue, wrapWrap());
        card.addView(header, matchWrap());

        TextView note = makeText("عتبة تغيّر المجال فوق المرجع. الضجيج العالي يرفع الحد تلقائيًا لتقليل الإنذارات الكاذبة.",
                11, SECONDARY, false);
        note.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(6);
        card.addView(note, noteParams);

        sensitivitySeekBar = new SeekBar(this);
        sensitivitySeekBar.setMax((int) ((MAX_SENSITIVITY - MIN_SENSITIVITY) * 2));
        sensitivitySeekBar.setProgressTintList(ColorStateList.valueOf(TEAL));
        sensitivitySeekBar.setThumbTintList(ColorStateList.valueOf(TEAL));
        sensitivitySeekBar.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF2B3D47));
        double current = processor.getSensitivity();
        sensitivitySeekBar.setProgress((int) Math.round((current - MIN_SENSITIVITY) * 2));
        sensitivitySeekBar.setContentDescription("حساسية التنبيه بالميكروتسلا");
        LinearLayout.LayoutParams seekParams = matchWrap();
        seekParams.topMargin = dp(5);
        card.addView(sensitivitySeekBar, seekParams);
        sensitivitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                double value = MIN_SENSITIVITY + progress * 0.5;
                processor.setSensitivity(value);
                preferences.edit().putFloat("sensitivity_ut", (float) value).apply();
                thresholdValue.setText(format(value) + " µT");
                if (latestReading != null) {
                    render(processor.currentReading(SystemClock.elapsedRealtimeNanos()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        LinearLayout scale = horizontal();
        TextView low = makeText("أعلى حساسية", 10, SECONDARY, false);
        TextView high = makeText("أقل تنبيهًا", 10, SECONDARY, false);
        scale.addView(low, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        high.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        scale.addView(high, wrapWrap());
        card.addView(scale, matchWrap());
        parent.addView(card, matchWrap());
    }

    private void buildChartCard(LinearLayout parent) {
        LinearLayout card = card();
        addSectionHeader(card, "مسار الإشارة", "آخر 20 ثانية");
        TextView description = makeText("مقدار الانحراف عن خط الأساس أثناء المسح النشط.",
                11, SECONDARY, false);
        card.addView(description, matchWrap());
        chartView = new SignalChartView(this);
        LinearLayout.LayoutParams chartParams = matchWrap();
        chartParams.height = dp(148);
        chartParams.topMargin = dp(8);
        chartView.setThreshold(processor.getSensitivity());
        card.addView(chartView, chartParams);
        parent.addView(card, matchWrap());
    }

    private void buildAxisCard(LinearLayout parent) {
        LinearLayout card = card();
        addSectionHeader(card, "مكونات المجال", "µT");
        TextView description = makeText("قيم X وY وZ بعد تصحيح انحياز حساس Android عند استخدام نمط غير معاير.",
                11, SECONDARY, false);
        card.addView(description, matchWrap());
        addSpace(card, 10);

        LinearLayout axes = horizontal();
        axisXValue = addAxisTile(axes, "X");
        axisYValue = addAxisTile(axes, "Y");
        axisZValue = addAxisTile(axes, "Z");
        card.addView(axes, matchWrap());

        sensorInfo = makeText("بانتظار معلومات المستشعر…", 10, SECONDARY, false);
        sensorInfo.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams infoParams = matchWrap();
        infoParams.topMargin = dp(11);
        card.addView(sensorInfo, infoParams);
        parent.addView(card, matchWrap());
    }

    private TextView addAxisTile(LinearLayout parent, String axis) {
        LinearLayout tile = vertical();
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(7), dp(9), dp(7), dp(9));
        tile.setBackground(rounded(SURFACE_DARK, BORDER, 12));
        TextView name = makeText(axis, 10, SECONDARY, true);
        name.setGravity(Gravity.CENTER);
        TextView value = makeText("—", 14, PRIMARY, true);
        value.setGravity(Gravity.CENTER);
        tile.addView(name, matchWrap());
        LinearLayout.LayoutParams valueParams = matchWrap();
        valueParams.topMargin = dp(4);
        tile.addView(value, valueParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        parent.addView(tile, params);
        return value;
    }

    private void buildAlertsCard(LinearLayout parent) {
        LinearLayout card = card();
        addSectionHeader(card, "تنبيهات الاقتراب", "اختياري");
        TextView description = makeText("يعمل التنبيه مرة عند عبور العتبة، لا على كل عينة.",
                11, SECONDARY, false);
        card.addView(description, matchWrap());
        addSpace(card, 11);

        LinearLayout buttons = horizontal();
        soundButton = makeButton("الصوت: متوقف", false);
        vibrationButton = makeButton("الاهتزاز: متوقف", false);
        LinearLayout.LayoutParams optionParams = new LinearLayout.LayoutParams(0,
                dp(48), 1f);
        optionParams.setMargins(dp(3), 0, dp(3), 0);
        buttons.addView(soundButton, optionParams);
        LinearLayout.LayoutParams vibrationParams = new LinearLayout.LayoutParams(0,
                dp(48), 1f);
        vibrationParams.setMargins(dp(3), 0, dp(3), 0);
        buttons.addView(vibrationButton, vibrationParams);
        card.addView(buttons, matchWrap());

        soundButton.setOnClickListener(view -> {
            soundEnabled = !soundEnabled;
            preferences.edit().putBoolean("sound_enabled", soundEnabled).apply();
            updateToggleButtons();
        });
        vibrationButton.setOnClickListener(view -> {
            vibrationEnabled = !vibrationEnabled;
            preferences.edit().putBoolean("vibration_enabled", vibrationEnabled).apply();
            updateToggleButtons();
        });
        parent.addView(card, matchWrap());
    }

    private void buildSessionCard(LinearLayout parent) {
        LinearLayout card = card();
        addSectionHeader(card, "تسجيل الجلسة", "محلي على الهاتف");
        exportHint = makeText("تُسجّل نقاط المسح أثناء تشغيله فقط. لا تُرفع البيانات ولا تُحفظ تلقائيًا.",
                11, SECONDARY, false);
        exportHint.setLineSpacing(dp(2), 1f);
        card.addView(exportHint, matchWrap());
        exportButton = makeButton("تصدير CSV", false);
        exportButton.setEnabled(false);
        exportButton.setAlpha(0.48f);
        LinearLayout.LayoutParams exportParams = matchWrap();
        exportParams.topMargin = dp(11);
        card.addView(exportButton, exportParams);
        exportButton.setOnClickListener(view -> exportSession());
        parent.addView(card, matchWrap());
    }

    private void buildLimitsCard(LinearLayout parent) {
        LinearLayout card = card();
        card.setBackground(rounded(0xFF15232A, 0xFF55452F, 18));
        TextView title = makeText("حدود فيزيائية — بلا ادعاءات", 14, AMBER, true);
        TextView body = makeText(
                "الهاتف يقيس المجال المغناطيسي؛ قد يستجيب للحديد والصلب والمغانط. لا يكشف الذهب أو النحاس أو الألمنيوم مباشرة، ولا يحدد نوع الجسم أو عمقه. الشاحن ومكبر الصوت والغطاء المغناطيسي قد تسبب إشارة مشابهة.",
                11, SECONDARY, false);
        body.setLineSpacing(dp(2), 1f);
        card.addView(title, matchWrap());
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(7);
        card.addView(body, bodyParams);

        TextView help = makeButton("طريقة الاستخدام والمعايرة", false);
        LinearLayout.LayoutParams helpParams = matchWrap();
        helpParams.topMargin = dp(10);
        card.addView(help, helpParams);
        help.setOnClickListener(view -> showHelp());
        parent.addView(card, matchWrap());
    }

    private void buildCreditsCard(LinearLayout parent) {
        LinearLayout card = card();
        addSectionHeader(card, "Credits & developer", "MIT");
        TextView creditLine = makeText("All Credits go to: M-Eleven", 14, TEAL, true);
        card.addView(creditLine, matchWrap());
        TextView developerName = makeText("Mohamed El-Sayed Abdelfatah", 12, PRIMARY, true);
        LinearLayout.LayoutParams nameParams = matchWrap();
        nameParams.topMargin = dp(5);
        card.addView(developerName, nameParams);

        addCreditLink(card, "YouTube  ·  @M11-Dev",
                view -> openWebLink("https://www.youtube.com/@M11-Dev"));
        addCreditLink(card, "GitHub  ·  @M11-Developer",
                view -> openWebLink("https://github.com/M11-Developer"));
        addCreditLink(card, "TikTok  ·  @m11_dev",
                view -> openWebLink("https://www.tiktok.com/@m11_dev"));
        addCreditLink(card, "البريد  ·  ma7292537@gmail.com", view -> openEmail());
        addCreditLink(card, "WhatsApp  ·  01027653109",
                view -> openWebLink("https://wa.me/201027653109"));
        addCreditLink(card, "اتصال هاتفي  ·  01027653109", view -> openPhoneDialer());

        TextView licenseNote = makeText("الترخيص: MIT  ·  راجع ملف LICENSE في المشروع.",
                10, SECONDARY, false);
        LinearLayout.LayoutParams licenseParams = matchWrap();
        licenseParams.topMargin = dp(10);
        card.addView(licenseNote, licenseParams);
        parent.addView(card, matchWrap());
    }

    private TextView addCreditLink(LinearLayout parent, String text,
                                   View.OnClickListener listener) {
        TextView link = makeText(text, 12, TEAL, true);
        link.setPadding(dp(12), dp(10), dp(12), dp(10));
        link.setMinHeight(dp(44));
        link.setBackground(rounded(SURFACE_DARK, BORDER, 12));
        link.setClickable(true);
        link.setFocusable(true);
        link.setOnClickListener(listener);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        parent.addView(link, params);
        return link;
    }

    private void openWebLink(String url) {
        launchExternalIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void openEmail() {
        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:ma7292537@gmail.com"));
        launchExternalIntent(intent);
    }

    private void openPhoneDialer() {
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.setData(Uri.parse("tel:01027653109"));
        launchExternalIntent(intent);
    }

    private void launchExternalIntent(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(this, "لا يوجد تطبيق مناسب لفتح هذا الرابط.", Toast.LENGTH_LONG).show();
        }
    }

    private void onMainAction() {
        if (magneticSensor == null || !sensorRegistered) {
            Toast.makeText(this, "لا يتوفر مستشعر مغناطيسي نشط على هذا الهاتف.", Toast.LENGTH_LONG).show();
            return;
        }
        if (processor.isCalibrating()) {
            processor.cancelCalibration();
            calibrationFailureSticky = false;
            refreshCurrentReading();
            return;
        }
        if (!processor.hasBaseline()) {
            beginCalibration();
            return;
        }
        if (processor.isScanning()) {
            stopScanning();
        } else {
            startScanning();
        }
    }

    private void beginCalibration() {
        if (magneticSensor == null || !sensorRegistered) {
            Toast.makeText(this, "لا يمكن المعايرة دون مستشعر مغناطيسي.", Toast.LENGTH_LONG).show();
            return;
        }
        if (processor.isScanning()) {
            stopScanning();
        }
        calibrationFailureSticky = false;
        processor.beginCalibration(SystemClock.elapsedRealtimeNanos());
        chartView.clear();
        refreshCurrentReading();
    }

    private void startScanning() {
        if (!processor.hasBaseline()) {
            beginCalibration();
            return;
        }
        sessionRows.clear();
        sessionStartedNs = SystemClock.elapsedRealtimeNanos();
        lastChartSampleNs = 0L;
        chartView.clear();
        processor.setScanning(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshCurrentReading();
    }

    private void stopScanning() {
        processor.setScanning(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshCurrentReading();
    }

    private void refreshCurrentReading() {
        latestReading = processor.currentReading(SystemClock.elapsedRealtimeNanos());
        render(latestReading);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && magneticSensor != null) {
            sensorRegistered = sensorManager.registerListener(
                    this, magneticSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (!sensorRegistered) {
            processor.setScanning(false);
            if (processor.isCalibrating()) {
                processor.cancelCalibration();
            }
        }
        refreshCurrentReading();
    }

    @Override
    protected void onPause() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        sensorRegistered = false;
        if (processor.isScanning()) {
            processor.setScanning(false);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (processor.isCalibrating()) {
            processor.cancelCalibration();
        }
        stopTone();
        refreshCurrentReading();
        super.onPause();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 3) {
            return;
        }
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        if (usingUncalibratedSensor && event.values.length >= 6) {
            // Android's uncalibrated type appends the estimated hard-iron bias in values 3..5.
            x -= event.values[3];
            y -= event.values[4];
            z -= event.values[5];
        }

        sensorAccuracy = event.accuracy;
        latestReading = processor.addSample(x, y, z, event.timestamp);
        if (latestReading.calibrationCompleted) {
            calibrationFailureSticky = false;
        }
        if (latestReading.calibrationFailed) {
            calibrationFailureSticky = true;
        }
        if (latestReading.justTriggered) {
            alertUser();
        }

        if (latestReading.scanning && event.timestamp - lastChartSampleNs >= CHART_INTERVAL_NS) {
            lastChartSampleNs = event.timestamp;
            chartView.addSample(latestReading.anomaly);
            recordSessionSample(latestReading, event.timestamp);
        }

        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - lastUiUpdateMs >= 50L) {
            lastUiUpdateMs = nowMs;
            render(latestReading);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        sensorAccuracy = accuracy;
        if (latestReading != null) {
            render(latestReading);
        }
    }

    private void alertUser() {
        if (soundEnabled) {
            try {
                if (toneGenerator == null) {
                    toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
                }
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 120);
            } catch (RuntimeException ignored) {
                // Audio is optional; a missing audio route must not interrupt sensing.
            }
        }
        if (vibrationEnabled && vibrator != null && vibrator.hasVibrator()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(65L);
                }
            } catch (RuntimeException ignored) {
                // Haptics are optional and disabled if the device cannot provide them.
            }
        }
    }

    private void stopTone() {
        if (toneGenerator != null) {
            try {
                toneGenerator.stopTone();
            } catch (RuntimeException ignored) {
                // The generator may already be released by the system audio service.
            }
        }
    }

    private void recordSessionSample(SignalProcessor.Reading reading, long timestampNs) {
        if (sessionRows.size() >= MAX_SESSION_ROWS || sessionStartedNs <= 0L) {
            return;
        }
        long elapsedMs = Math.max(0L, (timestampNs - sessionStartedNs) / 1_000_000L);
        sessionRows.add(String.format(Locale.US,
                "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s\n",
                elapsedMs, reading.x, reading.y, reading.z, reading.filteredMagnitude,
                reading.baseline, reading.deviation, reading.threshold,
                reading.alerting ? "true" : "false"));
        if (exportButton != null) {
            exportButton.setEnabled(!sessionRows.isEmpty());
            exportButton.setAlpha(sessionRows.isEmpty() ? 0.48f : 1f);
            exportButton.setText("تصدير CSV  ·  " + sessionRows.size());
        }
    }

    private void exportSession() {
        if (sessionRows.isEmpty()) {
            Toast.makeText(this, "ابدأ المسح أولاً لتسجيل بيانات.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "mirsad-field-scan.csv");
        try {
            startActivityForResult(intent, REQUEST_EXPORT_CSV);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "تعذر فتح أداة حفظ الملفات على هذا الجهاز.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_CSV || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri destination = data.getData();
        try (OutputStream output = getContentResolver().openOutputStream(destination)) {
            if (output == null) {
                throw new IOException("No output stream for the selected document");
            }
            StringBuilder csv = new StringBuilder();
            csv.append("elapsed_ms,x_uT,y_uT,z_uT,field_uT,baseline_uT,deviation_uT,threshold_uT,alert\n");
            for (String row : sessionRows) {
                csv.append(row);
            }
            output.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "تم حفظ " + sessionRows.size() + " قراءة CSV.", Toast.LENGTH_LONG).show();
        } catch (IOException | SecurityException exception) {
            Toast.makeText(this, "تعذر حفظ ملف CSV: " + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void render(SignalProcessor.Reading reading) {
        if (reading == null) {
            return;
        }
        latestReading = reading;
        if (gaugeView != null) {
            gaugeView.setReading(reading);
        }
        if (fieldValue != null) {
            fieldValue.setText(format(reading.filteredMagnitude) + " µT");
            baselineValue.setText(reading.hasBaseline ? format(reading.baseline) + " µT" : "— µT");
            noiseValue.setText(reading.hasBaseline ? format(reading.noiseFloor) + " µT" : "— µT");
            thresholdValue.setText(format(reading.threshold) + " µT");
            axisXValue.setText(formatSigned(reading.x));
            axisYValue.setText(formatSigned(reading.y));
            axisZValue.setText(formatSigned(reading.z));
        }
        if (chartView != null) {
            chartView.setThreshold(reading.threshold);
        }
        if (calibrationProgress != null) {
            calibrationProgress.setVisibility(reading.calibrating ? View.VISIBLE : View.GONE);
            calibrationProgress.setProgress(reading.calibrationProgress);
            if (reading.calibrating) {
                calibrationText.setText("تُجمع العينات… " + reading.calibrationProgress
                        + "%  ·  " + reading.calibrationSamples + " عينة");
            } else if (reading.hasBaseline) {
                calibrationText.setText("مرجع المكان " + format(reading.baseline)
                        + " µT  ·  أعد المعايرة عند الانتقال إلى غرفة أخرى.");
            } else if (calibrationFailureSticky) {
                calibrationText.setText("لم تكتمل المعايرة؛ أبعد الهاتف عن المغانط وحاول مجددًا.");
            } else {
                calibrationText.setText("المعايرة تثبّت المجال المحيط ولا تزيد قدرة المستشعر نفسه.");
            }
        }
        updateActionControls(reading);
        updateToggleButtons();
        updateStatus(reading);
        updateSensorInfo();
        updateSessionControls();
    }

    private void updateActionControls(SignalProcessor.Reading reading) {
        boolean available = magneticSensor != null && sensorRegistered;
        if (!available) {
            actionButton.setText("المستشعر غير متاح");
            actionButton.setEnabled(false);
            actionButton.setAlpha(0.5f);
            recalibrateButton.setVisibility(View.GONE);
            return;
        }
        actionButton.setEnabled(true);
        actionButton.setAlpha(1f);
        if (reading.calibrating) {
            actionButton.setText("إلغاء المعايرة");
            recalibrateButton.setVisibility(View.GONE);
        } else if (reading.scanning) {
            actionButton.setText("إيقاف المسح");
            recalibrateButton.setVisibility(View.VISIBLE);
        } else if (reading.hasBaseline) {
            actionButton.setText("بدء المسح");
            recalibrateButton.setVisibility(View.VISIBLE);
        } else {
            actionButton.setText("معايرة خط الأساس · 4 ثوانٍ");
            recalibrateButton.setVisibility(View.GONE);
        }
    }

    private void updateStatus(SignalProcessor.Reading reading) {
        if (magneticSensor == null || !sensorRegistered) {
            stateTitle.setText("لا يوجد مستشعر مغناطيسي نشط");
            stateMessage.setText("هذا الهاتف لا يوفّر قراءة صالحة للمجال المغناطيسي؛ لن يعرض التطبيق أرقامًا مصطنعة.");
            setBadge("غير متاح", RED);
            return;
        }
        if (reading.calibrating) {
            stateTitle.setText("تثبيت مرجع البيئة");
            stateMessage.setText("اترك الهاتف ثابتًا وبعيدًا عن المغانط. نحتاج عدة ثوانٍ من القياس الفعلي.");
            setBadge("معايرة", AMBER);
        } else if (calibrationFailureSticky) {
            stateTitle.setText("لم تكتمل المعايرة");
            stateMessage.setText("أبعد الهاتف عن المغناطيس القوي أو غطاء الهاتف المغناطيسي، ثم أعد المحاولة.");
            setBadge("أعد المحاولة", AMBER);
        } else if (reading.saturated) {
            stateTitle.setText("المستشعر قريب من حد القياس");
            stateMessage.setText("قد يكون الهاتف ملاصقًا لمغناطيس قوي. ابتعد عنه ثم أعد معايرة المكان.");
            setBadge("تشبّع محتمل", RED);
        } else if (!reading.hasBaseline) {
            stateTitle.setText("خذ مرجعًا قبل المسح");
            stateMessage.setText("أبعد الهاتف عن المعدن والشاحن، ثبّته، ثم اضغط معايرة خط الأساس.");
            setBadge("جاهز للمعايرة", TEAL);
        } else if (reading.scanning && reading.alerting) {
            stateTitle.setText("تغيّر مغناطيسي قوي");
            stateMessage.setText("قد يكون حديدًا أو مغناطيسًا أو تداخلًا كهربائيًا؛ الإشارة لا تحدد نوع الجسم.");
            setBadge("إشارة مرتفعة", RED);
        } else if (reading.scanning && reading.threshold > processor.getSensitivity() * 1.8) {
            stateTitle.setText("بيئة كثيرة التشويش");
            stateMessage.setText("رُفعت العتبة تلقائيًا إلى " + format(reading.threshold)
                    + " µT. ابتعد عن الشواحن ومكبرات الصوت وأعد المعايرة إن لزم.");
            setBadge("ضجيج مرتفع", AMBER);
        } else if (reading.scanning) {
            stateTitle.setText("المسح نشط · حرّك الهاتف ببطء");
            stateMessage.setText("الإنذار يعتمد على تغيّر المجال عن مرجع هذا المكان، وليس على قيمة مطلقة ثابتة.");
            setBadge("مسح نشط", TEAL);
        } else if (reading.threshold > processor.getSensitivity() * 1.8) {
            stateTitle.setText("المرجع محفوظ لكن الضجيج مرتفع");
            stateMessage.setText("العتبة الفعلية " + format(reading.threshold)
                    + " µT. ابتعد عن الشواحن والمكبرات ثم أعد المعايرة لتحسين وضوح المسح.");
            setBadge("مرجع مشوش", AMBER);
        } else {
            stateTitle.setText("خط الأساس جاهز");
            stateMessage.setText("مرّر الهاتف ببطء قرب الهدف. أعد المعايرة بعد تغيير المكان أو اتجاه البيئة.");
            setBadge("مرجع جاهز", TEAL);
        }

        if (sensorAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                || sensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            stateMessage.append(" إذا كانت دقة البوصلة منخفضة، حرّك الهاتف على شكل 8 ثم أعد المعايرة.");
        }
    }

    private void updateSensorInfo() {
        if (sensorInfo == null) {
            return;
        }
        if (magneticSensor == null) {
            sensorInfo.setText("لم يُعثر على مستشعر مجال مغناطيسي في هذا الجهاز.");
            return;
        }
        String sensorType = usingUncalibratedSensor
                ? "غير معاير · صُحّح انحياز Android"
                : "معاير من نظام Android";
        sensorInfo.setText(magneticSensor.getName() + "  ·  " + magneticSensor.getVendor()
                + "\n" + sensorType + "  ·  الدقة الاسمية "
                + format(magneticSensor.getResolution()) + " µT  ·  المدى "
                + format(magneticSensor.getMaximumRange()) + " µT");
    }

    private void updateSessionControls() {
        if (exportButton == null) {
            return;
        }
        boolean hasRows = !sessionRows.isEmpty();
        exportButton.setEnabled(hasRows);
        exportButton.setAlpha(hasRows ? 1f : 0.48f);
        exportButton.setText(hasRows ? "تصدير CSV  ·  " + sessionRows.size() : "تصدير CSV");
        if (exportHint != null && hasRows) {
            exportHint.setText("هذه الجلسة: " + sessionRows.size()
                    + " عينة. اضغط التصدير واختر مكان حفظ الملف.");
        }
    }

    private void updateToggleButtons() {
        if (soundButton != null) {
            soundButton.setText(soundEnabled ? "الصوت: يعمل" : "الصوت: متوقف");
            soundButton.setBackground(rounded(soundEnabled ? 0xFF12352F : SURFACE_DARK,
                    soundEnabled ? 0xFF36786B : BORDER, 14));
            soundButton.setTextColor(soundEnabled ? TEAL : SECONDARY);
        }
        if (vibrationButton != null) {
            vibrationButton.setText(vibrationEnabled ? "الاهتزاز: يعمل" : "الاهتزاز: متوقف");
            vibrationButton.setBackground(rounded(vibrationEnabled ? 0xFF12352F : SURFACE_DARK,
                    vibrationEnabled ? 0xFF36786B : BORDER, 14));
            vibrationButton.setTextColor(vibrationEnabled ? TEAL : SECONDARY);
        }
    }

    private void setBadge(String text, int color) {
        sensorBadge.setText(text);
        int background = color == RED ? 0xFF3B2529 : color == AMBER ? 0xFF3A3021 : 0xFF12332F;
        sensorBadge.setTextColor(color);
        sensorBadge.setBackground(rounded(background, color, 18));
    }

    private void showHelp() {
        String message = "1. المعايرة: أبعد الهاتف عن الحديد والمغانط والشواحن، ثبّته أربع ثوانٍ لتسجيل مرجع المجال المحلي. هذه معايرة للبيئة، وليست معايرة مصنع للحساس.\n\n"
                + "2. المسح: ابدأ المسح وحرّك الهاتف ببطء. اقترب قدر الإمكان من السطح؛ النتائج تعتمد بشدة على الهاتف والمسافة والاتجاه.\n\n"
                + "3. إذا كانت دقة البوصلة منخفضة، حرّك الهاتف في الهواء على شكل 8؛ هذا يطلب من نظام Android تحسين معايرة البوصلة، ثم أعد معايرة خط الأساس هنا.\n\n"
                + "4. الحساس يستجيب لتغيّر المجال، لا لهوية المعدن. الحديد والصلب قد يؤثران فيه؛ الذهب والنحاس والألمنيوم لا يمكن كشفها بهذا المستشعر وحده. لا تستخدمه كبديل لكاشف ملفات احترافي أو لفحص سلامة الأسلاك.";
        new AlertDialog.Builder(this)
                .setTitle("استخدام مسؤول ودقيق")
                .setMessage(message)
                .setPositiveButton("فهمت", null)
                .show();
    }

    private LinearLayout card() {
        LinearLayout layout = vertical();
        layout.setPadding(dp(16), dp(15), dp(16), dp(15));
        layout.setBackground(rounded(SURFACE, BORDER, 20));
        return layout;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return layout;
    }

    private void addSectionHeader(LinearLayout parent, String title, String trailing) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = makeText(title, 15, PRIMARY, true);
        row.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView small = makeText(trailing, 10, TEAL, true);
        small.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        row.addView(small, wrapWrap());
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        parent.addView(row, params);
    }

    private TextView makeText(String text, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private TextView makeBadge(String text) {
        TextView badge = makeText(text, 10, TEAL, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(8), dp(10), dp(8));
        badge.setBackground(rounded(0xFF12332F, 0xFF36786B, 18));
        return badge;
    }

    private TextView makeButton(String text, boolean primary) {
        TextView button = makeText(text, 14, primary ? BG : SECONDARY, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(11), dp(12), dp(11));
        button.setMinHeight(dp(48));
        button.setClickable(true);
        button.setFocusable(true);
        if (primary) {
            GradientDrawable gradient = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xFF62E7C8, 0xFF9BE6C3});
            gradient.setCornerRadius(dp(16));
            button.setBackground(gradient);
            button.setTextColor(0xFF09201E);
        } else {
            button.setBackground(rounded(SURFACE_DARK, BORDER, 14));
            button.setTextColor(SECONDARY);
        }
        return button;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private void addSpace(LinearLayout parent, int heightDp) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "—";
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private static String formatSigned(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "—";
        }
        return String.format(Locale.US, "%+.1f", value);
    }

    @Override
    protected void onDestroy() {
        stopTone();
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
        super.onDestroy();
    }

    private static final class BrandMarkView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        BrandMarkView(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setContentDescription("شعار مرصاد");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float radius = Math.min(getWidth(), getHeight()) * 0.47f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF153039);
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f * density);
            paint.setColor(0xFF31616A);
            canvas.drawCircle(cx, cy, radius - 1.5f * density, paint);
            paint.setStrokeWidth(2.2f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(TEAL);
            Path wave = new Path();
            wave.moveTo(cx - 13 * density, cy + 1 * density);
            wave.cubicTo(cx - 9 * density, cy - 12 * density,
                    cx - 3 * density, cy - 12 * density, cx + 1 * density, cy + 1 * density);
            wave.cubicTo(cx + 4 * density, cy + 10 * density,
                    cx + 9 * density, cy + 10 * density, cx + 13 * density, cy - 1 * density);
            canvas.drawPath(wave, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, 2.4f * density, paint);
        }
    }
}
