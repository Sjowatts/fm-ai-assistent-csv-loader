package com.github.fmaiassistent.web.ui;

import com.github.fmaiassistent.service.CsvLoadAllService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.server.streams.UploadHandler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Loads a snapshot from FM view exports, for setups where FM's memory cannot be read.
 * Both files are optional individually, but a load replaces the whole snapshot, so the
 * dialog says plainly that sending only one of them clears the other.
 */
class CsvLoadDialog extends Dialog {
    private static final int MAX_UPLOAD_BYTES = 64 * 1024 * 1024;

    private final CsvLoadAllService csvLoad;
    private final Consumer<CsvLoadAllService.CsvLoadResult> loaded;
    private final AtomicReference<UploadedCsv> players = new AtomicReference<>(UploadedCsv.empty());
    private final AtomicReference<UploadedCsv> staff = new AtomicReference<>(UploadedCsv.empty());
    private final TextField gameDate = new TextField("In-game date");
    private final TextField managedClub = new TextField("Club you manage");
    private final Span summary = new Span();
    private final Button load = new Button("Load snapshot", VaadinIcon.DATABASE.create());

    CsvLoadDialog(CsvLoadAllService csvLoad, Consumer<CsvLoadAllService.CsvLoadResult> loaded) {
        this.csvLoad = csvLoad;
        this.loaded = loaded;
        setHeaderTitle("Load data from FM exports");
        addClassName("csv-load-dialog");
        setWidth("34rem");

        Span help = new Span(
                "Export a players view and a staff view from FM to CSV, then upload them here. "
                        + "A load replaces the whole snapshot, so upload both files together to keep both. "
                        + "Attributes shown as a range for unscouted players are stored as their midpoint.");
        help.addClassName("csv-load-help");

        gameDate.setPlaceholder("2026-08-01");
        gameDate.setHelperText("ISO date. Only needed when the view has no Age column.");
        gameDate.setWidthFull();
        managedClub.setPlaceholder("Aston Villa");
        managedClub.setHelperText("Optional. Squad tools need a club name to work against.");
        managedClub.setWidthFull();
        summary.addClassName("csv-load-summary");

        VerticalLayout body = new VerticalLayout(
                help,
                upload("Players export (.csv)", "or drop your players CSV here", players),
                upload("Staff export (.csv)", "or drop your staff CSV here", staff),
                gameDate,
                managedClub,
                summary);
        body.setPadding(false);
        body.setSpacing(true);
        add(body);

        load.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        load.setEnabled(false);
        load.addClickListener(ignored -> runLoad());
        Button cancel = new Button("Close", ignored -> close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        getFooter().add(cancel, load);
    }

    private Upload upload(String label, String dropLabel, AtomicReference<UploadedCsv> target) {
        Upload upload = new Upload(UploadHandler.inMemory((metadata, bytes) -> {
            if (bytes.length > MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException("CSV upload is larger than 64 MB");
            }
            target.set(new UploadedCsv(
                    metadata.fileName() == null ? "upload.csv" : metadata.fileName(),
                    new String(bytes, StandardCharsets.UTF_8)));
        }));
        Button chooser = new Button(label, VaadinIcon.UPLOAD.create());
        upload.setUploadButton(chooser);
        upload.setDropLabel(new Span(dropLabel));
        upload.setAcceptedFileTypes(".csv", "text/csv");
        upload.setMaxFileSize(MAX_UPLOAD_BYTES);
        upload.setMaxFiles(1);
        upload.setWidthFull();
        upload.addAllFinishedListener(ignored -> getUI().ifPresent(ui -> ui.access(this::refreshReadiness)));
        upload.addFileRemovedListener(ignored -> {
            target.set(UploadedCsv.empty());
            refreshReadiness();
        });
        upload.addFileRejectedListener(event -> Notification.show(event.getErrorMessage()));
        return upload;
    }

    private void refreshReadiness() {
        List<String> chosen = new ArrayList<>();
        if (players.get().present()) {
            chosen.add("players: " + players.get().name());
        }
        if (staff.get().present()) {
            chosen.add("staff: " + staff.get().name());
        }
        summary.setText(chosen.isEmpty() ? "" : String.join("  |  ", chosen));
        load.setEnabled(!chosen.isEmpty());
    }

    private void runLoad() {
        UI ui = UI.getCurrent();
        UploadedCsv playerFile = players.get();
        UploadedCsv staffFile = staff.get();
        String label = sources(playerFile, staffFile);
        load.setEnabled(false);
        load.setText("Loading...");

        Thread.ofVirtual().name("csv-snapshot-load").start(() -> {
            try {
                CsvLoadAllService.CsvLoadResult result = csvLoad.loadUploads(
                        playerFile.content(),
                        staffFile.content(),
                        label,
                        emptyToNull(gameDate.getValue()),
                        emptyToNull(managedClub.getValue()));
                ui.access(() -> {
                    loaded.accept(result);
                    Notification.show(describe(result), 6000, Notification.Position.TOP_CENTER);
                    close();
                });
            } catch (RuntimeException exception) {
                ui.access(() -> {
                    load.setEnabled(true);
                    load.setText("Load snapshot");
                    Notification.show(
                            "CSV load failed: " + exception.getMessage(), 8000, Notification.Position.TOP_CENTER);
                });
            }
        });
    }

    private static String describe(CsvLoadAllService.CsvLoadResult result) {
        StringBuilder text = new StringBuilder("Loaded " + result.players() + " players and "
                + result.staff() + " staff from CSV");
        List<String> missing = new ArrayList<>(result.playerDiagnostics().missingColumns());
        result.staffDiagnostics().missingColumns().stream()
                .filter(column -> !missing.contains(column))
                .forEach(missing::add);
        if (!missing.isEmpty()) {
            text.append(". Columns not in the export: ").append(String.join(", ", missing));
        }
        int estimated = result.playerDiagnostics().estimatedRows() + result.staffDiagnostics().estimatedRows();
        if (estimated > 0) {
            text.append(". ").append(estimated).append(" rows hold scouting estimates");
        }
        return text.toString();
    }

    private static String sources(UploadedCsv players, UploadedCsv staff) {
        List<String> names = new ArrayList<>();
        if (players.present()) {
            names.add(players.name());
        }
        if (staff.present()) {
            names.add(staff.name());
        }
        return String.join("; ", names);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record UploadedCsv(String name, String content) {
        static UploadedCsv empty() {
            return new UploadedCsv("", null);
        }

        boolean present() {
            return content != null && !content.isBlank();
        }
    }
}
