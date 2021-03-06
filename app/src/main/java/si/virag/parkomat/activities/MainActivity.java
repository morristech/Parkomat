package si.virag.parkomat.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import de.greenrobot.event.EventBus;
import org.threeten.bp.LocalTime;
import org.threeten.bp.ZoneId;
import org.threeten.bp.format.DateTimeFormatter;
import org.threeten.bp.temporal.ChronoUnit;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import icepick.Icepick;
import icepick.State;
import rx.Observer;
import rx.Subscriber;
import rx.functions.Action1;
import si.virag.parkomat.ParkomatApplication;
import si.virag.parkomat.R;
import si.virag.parkomat.models.Car;
import si.virag.parkomat.modules.CarsManager;
import si.virag.parkomat.modules.SmsHandler;
import si.virag.parkomat.modules.TimeManager;
import si.virag.parkomat.modules.ZoneManager;
import si.virag.parkomat.receivers.SmsReceiver;

public class MainActivity extends AppCompatActivity {

    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm ").withZone(ZoneId.systemDefault());
    public static final String PREF_WELCOME_DONE = "Welcome.Wizard.Done";

    @BindView(R.id.main_container)
    View container;

    @BindView(R.id.main_registration_plate)
    TextView registrationPlate;
    @BindView(R.id.main_car_name)
    TextView carName;
    @BindView(R.id.main_parking_zone_txt)
    TextView zoneName;
    @BindView(R.id.main_parking_time_txt)
    TextView timeName;
    @BindView(R.id.main_parking_zone_info)
    TextView zoneInfo;

    @BindView(R.id.main_button_pay)
    Button payButton;

    @Inject
    CarsManager carsManager;

    @Inject
    ZoneManager zoneManager;

    @Inject
    TimeManager timeManager;

    @Inject
    SmsHandler smsHandler;

    @State
    String currentlySelectedZone;

    @State
    LocalTime currentlySelectedTime;

    @State
    int calculatedHoursToPay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ButterKnife.bind(this);
        ParkomatApplication.get(this).inject(this);
        Icepick.restoreInstanceState(this, savedInstanceState);

        // Check if welcome wizard has run
        if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean(PREF_WELCOME_DONE, false) || !carsManager.hasCars()) {
            Intent i = new Intent(this, WelcomeActivity.class);
            startActivity(i);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        showCar(carsManager.lastSelectedCarIndex());
        setZone(zoneManager.lastSelectedZone());
        setTime(timeManager.initialDisplayedTime());
        updatePriceOnButton();

        EventBus.getDefault().register(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        EventBus.getDefault().unregister(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        switch (id) {
            case R.id.menu_main_carmanager:
                Intent carManagerIntent = new Intent(this, CarManagerActivity.class);
                startActivity(carManagerIntent);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void setZone(@NonNull String zone) {
        zoneName.setText(zone);
        currentlySelectedZone = zone;
        zoneInfo.setText(zoneManager.getZoneInfoString(zone));

        updatePriceOnButton();
    }

    private void setTime(@NonNull LocalTime time) {
        if (currentlySelectedZone == null) throw new IllegalStateException("Need to know zone before setting time.");
        currentlySelectedTime = time;

        // Normalize time to max hours
        calculatedHoursToPay = zoneManager.getValidHoursToPayFromThisMoment(time, currentlySelectedZone);
        if (calculatedHoursToPay < 1) {
            timeName.setText("--");
            return;
        }

        LocalTime timeToDisplay = LocalTime.now().plus(calculatedHoursToPay, ChronoUnit.HOURS);

        SpannableStringBuilder string = new SpannableStringBuilder(timeFormatter.format(timeToDisplay));
        String hourDifferenceString = "(" + (calculatedHoursToPay == zoneManager.maxHoursForZone(currentlySelectedZone) ? "max " : "") + getResources().getQuantityString(R.plurals.hours_hint, calculatedHoursToPay, calculatedHoursToPay) + ")";
        string.append(hourDifferenceString);
        string.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorAccent)), string.length() - hourDifferenceString.length(), string.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        string.setSpan(new AbsoluteSizeSpan(14, true), string.length() - hourDifferenceString.length(), string.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        timeName.setText(string);
        updatePriceOnButton();
    }

    private void showCar(int index) {
        carsManager.getCars().elementAt(index).single().subscribe(new Observer<Car>() {
            @Override
            public void onCompleted() {

            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onNext(Car car) {
                carName.setText(car.name);
                registrationPlate.setText(car.registrationPlate);
            }
        });
    }

    private void updatePriceOnButton() {
        if (calculatedHoursToPay < 1) {
            payButton.setText(R.string.button_pay_free);
            payButton.setEnabled(false);
        } else {
            payButton.setText(getString(R.string.button_pay, zoneManager.getPriceForZone(currentlySelectedZone, calculatedHoursToPay)));
            payButton.setEnabled(true);
        }
    }

    @OnClick(R.id.main_registration_plate)
    public void onCarNameClick() {
        carsManager.pickCar(this).subscribe(new Subscriber<Integer>() {
            @Override
            public void onCompleted() {

            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onNext(Integer carIndex) {
                showCar(carIndex);
            }
        });
    }

    @OnClick(R.id.main_parking_time)
    public void onParkingTimeClick() {
        timeManager.pickTime(this, currentlySelectedTime).subscribe(new Action1<LocalTime>() {
            @Override
            public void call(LocalTime instant) {
                setTime(instant);
            }
        });
    }

    @OnClick(R.id.main_parking_zone)
    public void onParkingZoneClick() {
        zoneManager.pickZone(this).subscribe(new Action1<String>() {
            @Override
            public void call(String selectedZone) {
                setZone(selectedZone);
                setTime(currentlySelectedTime);
            }
        });
    }

    @OnClick(R.id.main_button_pay)
    public void onParkingPayClick() {
        String plateNormalized = registrationPlate.getText().toString().replaceAll("[^A-Za-z0-9]", "");
        smsHandler.payForParking(this, currentlySelectedZone, plateNormalized, calculatedHoursToPay).subscribe(new Subscriber<Void>() {
            @Override
            public void onCompleted() {
                // Nothing TBD
            }

            @Override
            public void onError(Throwable e) {
                Snackbar.make(container, "Sporočila ni bilo moč poslati.", Snackbar.LENGTH_LONG).show();
            }

            @Override
            public void onNext(Void aVoid) {
                // Nothing TBD
            }
        });
    }

    @OnClick(R.id.main_button_topup)
    public void onTopUpClick() {
        Intent intent = new Intent(Intent.ACTION_DIAL);
        intent.setData(Uri.parse("tel:" + SmsHandler.PHONE_NUMBER));
        startActivity(intent);
    }

    @OnClick(R.id.main_button_funds)
    public void onFundsClick() {
        smsHandler.checkForFunds(this).subscribe(new Subscriber<Void>() {
            @Override
            public void onCompleted() {

            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onNext(Void aVoid) {

            }
        });
    }

    public void onEventMainThread(SmsReceiver.ReceivedSmsMessage message) {
        smsHandler.handleReceivedMessage(this, message);
    }


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Icepick.saveInstanceState(this, outState);
    }
}
