package com.posiciontelefono;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;

/** Dialogs shared by the track list and the map: give a name, delete for good. */
final class TrackDialogs {

    private TrackDialogs() {}

    static void rename(Activity a, File f, Runnable onDone) {
        EditText input = new EditText(a);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(Format.MAX_LABEL)});
        input.setHint(R.string.name_hint);
        input.setText(TrackStore.label(a, f));
        input.setSelection(input.length());

        FrameLayout box = new FrameLayout(a);
        int pad = Math.round(20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(R.string.name_title)
                .setMessage(Format.trackTitle(f.getName()))
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> {
                    TrackStore.setLabel(a, f, input.getText().toString());
                    onDone.run();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        input.setOnEditorActionListener((v, action, event) -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            return true;
        });
        dialog.show();
    }

    static void delete(Activity a, File f, Runnable onDone) {
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.delete_title, TrackStore.title(a, f)))
                .setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete_forever, (d, w) -> {
                    if (TrackStore.delete(a, f)) onDone.run();
                    else Toast.makeText(a, R.string.delete_failed, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(a.getColor(R.color.stop));
    }
}
