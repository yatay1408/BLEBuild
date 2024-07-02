package de.kai_morich.simple_bluetooth_le_terminal;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.util.UUID;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private EditText editTextServiceUUID;
    private EditText editTextReadUUID;
    private EditText editTextWriteUUID;
    private UUID serviceUUID;
    private UUID readUUID;
    private UUID writeUUID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);

        // Khởi tạo các trường nhập UUID
        editTextServiceUUID = findViewById(R.id.editTextServiceUUID);
        editTextReadUUID = findViewById(R.id.editTextReadUUID);
        editTextWriteUUID = findViewById(R.id.editTextWriteUUID);

        Button buttonConnect = findViewById(R.id.buttonConnect);
        buttonConnect.setOnClickListener(v -> {
            try {
                serviceUUID = UUID.fromString(editTextServiceUUID.getText().toString());
                readUUID = UUID.fromString(editTextReadUUID.getText().toString());
                writeUUID = UUID.fromString(editTextWriteUUID.getText().toString());
            } catch (IllegalArgumentException e) {
                Toast.makeText(MainActivity.this, "Invalid UUID format", Toast.LENGTH_SHORT).show();
                return;
            }

            // Cập nhật DevicesFragment với các UUID được nhập
            DevicesFragment fragment = (DevicesFragment) getSupportFragmentManager().findFragmentByTag("devices");
            if (fragment != null) {
                fragment.updateUUIDs(serviceUUID, readUUID, writeUUID);
            }
        });

        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount() > 0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}
