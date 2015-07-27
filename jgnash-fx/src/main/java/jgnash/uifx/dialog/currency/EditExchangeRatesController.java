/*
 * jGnash, account personal finance application
 * Copyright (C) 2001-2015 Craig Cavanaugh
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received account copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jgnash.uifx.dialog.currency;

import java.math.BigDecimal;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import jgnash.engine.Engine;
import jgnash.engine.EngineFactory;
import jgnash.engine.ExchangeRate;
import jgnash.engine.ExchangeRateHistoryNode;
import jgnash.engine.MathConstants;
import jgnash.engine.message.Message;
import jgnash.engine.message.MessageBus;
import jgnash.engine.message.MessageChannel;
import jgnash.engine.message.MessageListener;
import jgnash.engine.message.MessageProperty;
import jgnash.uifx.control.BigDecimalTableCell;
import jgnash.uifx.control.CurrencyComboBox;
import jgnash.uifx.control.DatePickerEx;
import jgnash.uifx.control.DecimalTextField;
import jgnash.uifx.control.ShortDateTableCell;
import jgnash.uifx.util.FXMLUtils;
import jgnash.uifx.util.InjectFXML;
import jgnash.util.ResourceUtils;

/**
 * Controller for editing currency exchange rates
 *
 * @author Craig Cavanaugh
 */
public class EditExchangeRatesController implements MessageListener {

    @InjectFXML
    private final ObjectProperty<Scene> parentProperty = new SimpleObjectProperty<>();

    @FXML
    private DatePickerEx datePicker;

    @FXML
    private CurrencyComboBox baseCurrencyComboBox;

    @FXML
    private CurrencyComboBox targetCurrencyComboBox;

    @FXML
    private DecimalTextField exchangeRateField;

    @FXML
    private Button addButton;

    @FXML
    private Button deleteButton;

    @FXML
    private Button clearButton;

    @FXML
    private TableView<ExchangeRateHistoryNode> exchangeRateTable;

    @FXML
    private Button updateOnlineButton;

    @FXML
    private ProgressBar progressBar;

    @FXML
    private Button stopButton;

    @FXML
    private ResourceBundle resources;

    private final SimpleObjectProperty<ExchangeRateHistoryNode> selectedHistoryNode = new SimpleObjectProperty<>();

    private final SimpleObjectProperty<ExchangeRate> selectedExchangeRate = new SimpleObjectProperty<>();

    private Future<Void> updateFuture;

    public static void showAndWait() {
        final URL fxmlUrl = EditExchangeRatesController.class.getResource("EditExchangeRates.fxml");
        final Stage stage = FXMLUtils.loadFXML(fxmlUrl, ResourceUtils.getBundle());
        stage.setTitle(ResourceUtils.getString("Title.EditExchangeRates"));

        stage.showAndWait();
    }

    @FXML
    void initialize() {
        exchangeRateField.scaleProperty().setValue(MathConstants.EXCHANGE_RATE_ACCURACY);

        exchangeRateTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        exchangeRateTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        final TableColumn<ExchangeRateHistoryNode, Date> dateColumn = new TableColumn<>(resources.getString("Column.Date"));
        dateColumn.setCellValueFactory(param -> new SimpleObjectProperty<>(param.getValue().getDate()));
        dateColumn.setCellFactory(cell -> new ShortDateTableCell());
        exchangeRateTable.getColumns().add(dateColumn);

        final NumberFormat decimalFormat = NumberFormat.getInstance();
        if (decimalFormat instanceof DecimalFormat) {
            decimalFormat.setMinimumFractionDigits(MathConstants.EXCHANGE_RATE_ACCURACY);
            decimalFormat.setMaximumFractionDigits(MathConstants.EXCHANGE_RATE_ACCURACY);
        }

        final TableColumn<ExchangeRateHistoryNode, BigDecimal> rateColumn
                = new TableColumn<>(resources.getString("Column.ExchangeRate"));
        rateColumn.setCellValueFactory(param -> {
            if (param == null || selectedExchangeRate.get() == null) {
                return null;
            }

            if (selectedExchangeRate.get().getRateId().startsWith(baseCurrencyComboBox.getValue().getSymbol())) {
                return new SimpleObjectProperty<>(param.getValue().getRate());
            }

            return new SimpleObjectProperty<>(BigDecimal.ONE.divide(param.getValue().getRate(),
                    MathConstants.roundingMode));
        });
        rateColumn.setCellFactory(cell -> new BigDecimalTableCell<>(decimalFormat));
        exchangeRateTable.getColumns().add(rateColumn);

        baseCurrencyComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            handleExchangeRateSelectionChange();
        });

        targetCurrencyComboBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            handleExchangeRateSelectionChange();
        });

        selectedHistoryNode.bind(exchangeRateTable.getSelectionModel().selectedItemProperty());

        deleteButton.disableProperty().bind(selectedHistoryNode.isNull());

        clearButton.disableProperty().bind(exchangeRateField.textProperty().isEmpty());

        addButton.disableProperty().bind(exchangeRateField.textProperty().isEmpty()
                .or(Bindings.equal(baseCurrencyComboBox.getSelectionModel().selectedItemProperty(),
                        targetCurrencyComboBox.getSelectionModel().selectedItemProperty())));

        selectedExchangeRate.addListener((observable, oldValue, newValue) -> {
            Platform.runLater(EditExchangeRatesController.this::loadExchangeRateHistory);
        });

        selectedHistoryNode.addListener((observable, oldValue, newValue) -> {
            Platform.runLater(EditExchangeRatesController.this::updateForm);
        });

        MessageBus.getInstance().registerListener(this, MessageChannel.COMMODITY);

        // Install a listener to unregister from the message bus when the window closes
        parentProperty.addListener((observable, oldValue, scene) -> {
            if (scene != null) {
                scene.windowProperty().addListener((observable1, oldValue1, window) -> {
                    window.addEventHandler(WindowEvent.WINDOW_HIDING, event -> {
                        handleStopAction();
                        Logger.getLogger(EditExchangeRatesController.class.getName()).info("Unregistered from the message bus");
                        MessageBus.getInstance().unregisterListener(EditExchangeRatesController.this, MessageChannel.COMMODITY);
                    });
                });
            }
        });
    }

    private void updateForm() {
        if (selectedHistoryNode.get() != null) {
            datePicker.setDate(selectedHistoryNode.get().getDate());
            exchangeRateField.setDecimal(selectedHistoryNode.get().getRate());
        }
    }

    private void loadExchangeRateHistory() {
        System.out.println("loadExchangeRateHistory");

        if (selectedExchangeRate.get() == null) {
            exchangeRateTable.getItems().clear();
        } else {
            final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
            Objects.requireNonNull(engine);

            exchangeRateTable.getItems().setAll(selectedExchangeRate.get().getHistory());
            FXCollections.sort(exchangeRateTable.getItems());
        }
    }

    private void handleExchangeRateSelectionChange() {
        if (baseCurrencyComboBox.getValue() != null && targetCurrencyComboBox.getValue() != null) {

            final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
            Objects.requireNonNull(engine);

            selectedExchangeRate.setValue(engine.getExchangeRate(baseCurrencyComboBox.getValue(),
                    targetCurrencyComboBox.getValue()));
        }
    }

    @FXML
    private void handleAddAction() {
        final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
        Objects.requireNonNull(engine);

        engine.setExchangeRate(baseCurrencyComboBox.getValue(), targetCurrencyComboBox.getValue(),
                exchangeRateField.getDecimal(), datePicker.getDate());

        handleClearAction();
    }

    @FXML
    private void handleDeleteAction() {
        final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
        Objects.requireNonNull(engine);

        // Create a defensive list of the selected history nodes
        final List<ExchangeRateHistoryNode> historyNodes
                = new ArrayList<>(exchangeRateTable.getSelectionModel().getSelectedItems());

        for (final ExchangeRateHistoryNode historyNode : historyNodes) {
            engine.removeExchangeRateHistory(selectedExchangeRate.get(), historyNode);
        }
    }

    @FXML
    private void handleClearAction() {
        datePicker.setValue(LocalDate.now());
        exchangeRateField.clear();
    }

    @FXML
    private void handleUpdateAction() {
        final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
        Objects.requireNonNull(engine);

        updateOnlineButton.disableProperty().setValue(true);

        progressBar.progressProperty().setValue(-1);

        updateFuture = engine.startExchangeRateUpdate(1);

        try {
            updateFuture.get(5, TimeUnit.MINUTES);
        } catch (final InterruptedException | ExecutionException | TimeoutException e) { // intentionally interrupted
            Logger.getLogger(EditExchangeRatesController.class.getName()).log(Level.FINEST, e.getLocalizedMessage(), e);
        } finally {
            updateOnlineButton.disableProperty().setValue(false);
            progressBar.progressProperty().setValue(0);
        }
    }

    @FXML
    private void handleStopAction() {
        if (updateFuture != null) {
            updateFuture.cancel(true);
        }
    }

    @FXML
    private void handleCloseAction() {
        handleStopAction();

        ((Stage) parentProperty.get().getWindow()).close();
    }

    @Override
    public void messagePosted(final Message message) {
        switch (message.getEvent()) {
            case EXCHANGE_RATE_ADD:
            case EXCHANGE_RATE_REMOVE:
                final ExchangeRate rate = message.getObject(MessageProperty.EXCHANGE_RATE);
                if (rate.equals(selectedExchangeRate.get())) {
                    Platform.runLater(this::loadExchangeRateHistory);
                }
                break;
            default:
                break;
        }
    }
}