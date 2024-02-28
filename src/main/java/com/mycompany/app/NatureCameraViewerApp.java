/**
 * Copyright 2024 Esri
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.mycompany.app;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.esri.arcgisruntime.ArcGISRuntimeEnvironment;
import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.data.ArcGISFeature;

import com.esri.arcgisruntime.data.FeatureQueryResult;
import com.esri.arcgisruntime.data.QueryParameters;
import com.esri.arcgisruntime.data.ServiceFeatureTable;
import com.esri.arcgisruntime.layers.FeatureLayer;
import com.esri.arcgisruntime.mapping.BasemapStyle;
import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.mapping.Viewpoint;
import com.esri.arcgisruntime.mapping.view.IdentifyLayerResult;
import com.esri.arcgisruntime.mapping.view.MapView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.util.Callback;

/**
 * A simple app to display images captured by a nature camera app and stored as attachments to features.
 * <p>
 * Example capturing app is in <a href="https://github.com/mbcoder/nature-camera/tree/nature-camera">nature-camera</a>.
 */
public class NatureCameraViewerApp extends Application {

  record  ImageRecord (String dateString, Image imageData) {}

  private static final String serviceTableURL = "https://services1.arcgis.com/6677msI40mnLuuLr/ArcGIS/rest/services/NatureCamera/FeatureServer/0";
  private static final String imageTableURL = "https://services1.arcgis.com/6677msI40mnLuuLr/ArcGIS/rest/services/NatureCamera/FeatureServer/1";
  private ServiceFeatureTable cameraLocationFeatureTable;
  private ServiceFeatureTable imageFeatureTable;

  private MapView mapView;
  private ArcGISMap map;
  private final TextFlow textArea = new TextFlow();
  private final Text noCamera = new Text("No camera selected");

  private final TextField imageCountText = new TextField();
  private final ExecutorService executorService = Executors.newSingleThreadExecutor();
  private final AtomicBoolean shutdownNow = new AtomicBoolean();
  private final AtomicBoolean stopListing = new AtomicBoolean();
  private final IntegerProperty imageCount =new SimpleIntegerProperty();
  private ListenableFuture<IdentifyLayerResult> identifyGraphics;
  private static final ListView<ImageRecord> imageListView = new ListView<>();
  private final ObservableList<ImageRecord> imagesList = FXCollections.observableArrayList();

  // The date string is used to sort the list, so if the formatting is modified might need to change sorting
  private static final SimpleDateFormat imageDateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");

  private final BooleanProperty listIsLoading = new SimpleBooleanProperty();


  /**
   * Entry point for app which launches a JavaFX app instance.
   */
  public static void main(String[] args) {

    Application.launch(args);
  }

  /**
   * Method which contains logic for starting the Java FX application
   *
   * @param stage the primary stage for this application, onto which
   * the application scene can be set.
   * Applications may create other stages, if needed, but they will not be
   * primary stages.
   */
  @Override
  public void start(Stage stage) {

    ArcGISRuntimeEnvironment.setApiKey("AAPK2967d54657e342d398c129c8581b516686jXNGXVc6CFPZVRVIFV6YbjpO_fn7fz4S2ECOR2nkZkZXxyfl51Iy8AS6h3qcXV");

    // set the title and size of the stage and show it
    stage.setTitle("Nature camera image viewer");
    stage.setWidth(800);
    stage.setHeight(600);
    stage.show();

    // connect to cameraLocationFeatureTable we will be using to get a list of available cameras
    cameraLocationFeatureTable = new ServiceFeatureTable(serviceTableURL);
    cameraLocationFeatureTable.loadAsync();

    // connect to imageFeatureTable we will be using to get a list of available image attachments for selected camera
    imageFeatureTable = new ServiceFeatureTable(imageTableURL);
    imageFeatureTable.loadAsync();

    FeatureLayer featureLayer = new FeatureLayer(cameraLocationFeatureTable);
    mapView = new MapView();

    map = new ArcGISMap(BasemapStyle.ARCGIS_STREETS);
    map.getOperationalLayers().add(featureLayer);
    featureLayer.loadAsync();
    featureLayer.addDoneLoadingListener(() -> mapView.setViewpoint(new Viewpoint(featureLayer.getFullExtent())));
    mapView.setMap(map);
    map.loadAsync();



    SortedList<ImageRecord> sortedList = new SortedList<>(imagesList, (o1, o2) -> {
      if (o1 == null && o2 == null) {
        return 0;
      } else if (o1 == null) {
        return -1;
      } else if (o2 == null) {
        return 1;
      } else if (o1.dateString.equals(o2.dateString)) {
        return o1.imageData.hashCode() - o2.imageData.hashCode();
      }
      return o1.dateString.compareTo(o2.dateString);
    });

    imageListView.setItems(sortedList);

    imageListView.setCellFactory(new ImageCellFactory());

    imageCountText.textProperty().bind(
        Bindings.concat("Image count: ",
            imageCount.asString(),
            Bindings.when(listIsLoading).then("   (List is loading)").otherwise("")));

    VBox rightHandBox = new VBox();
    rightHandBox.getChildren().addAll(textArea, imageCountText, imageListView);
    // Want the list view to always fill remaining space
    VBox.setVgrow(imageListView, Priority.ALWAYS);

    noCamera.setFont(new Font(20));
    textArea.getChildren().add(noCamera);
    textArea.setPrefHeight(75);
    imageCount.set(0);

    SplitPane splitPane = new SplitPane();
    splitPane.getItems().addAll(mapView, rightHandBox);
    Scene scene = new Scene(splitPane);
    stage.setScene(scene);

    mapView.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
        // create a point from location clicked
        Point2D mapViewPoint = new Point2D(e.getX(), e.getY());

        // identify graphics on the graphics overlay
        identifyGraphics = mapView.identifyLayerAsync(featureLayer, mapViewPoint, 10, false);

        identifyGraphics.addDoneListener(() -> Platform.runLater(() ->
        {
          try {
            var res = identifyGraphics.get();
            imageCount.set(0);
            imagesList.clear();
            stopListing.set(true);
            textArea.getChildren().clear();

            if (!res.getElements().isEmpty()) {
              var ele = res.getElements().get(0);
              var currentNatureCameraId = ele.getAttributes().get("GlobalId").toString();
              executorService.execute(() -> findAndDisplayImages(currentNatureCameraId));

              Text cameraName = new Text(ele.getAttributes().get("CameraName").toString());
              cameraName.setFont(new Font(20));
              cameraName.setUnderline(true);
              cameraName.setTextAlignment(TextAlignment.CENTER);
              textArea.getChildren().addAll(new Text("Camera name: "), cameraName, new Text("\n"));

              Text globalId = new Text (ele.getAttributes().get("GlobalID").toString());
              textArea.getChildren().addAll(new Text("Global id: "), globalId, new Text("\n"));

              Text objectId = new Text (ele.getAttributes().get("ObjectId").toString());
              textArea.getChildren().addAll(new Text("ObjectId: "), objectId, new Text("\n"));
            } else {
              textArea.getChildren().add(noCamera);
            }
          } catch (InterruptedException | ExecutionException ex) {
            System.out.println(ex.getMessage());
            ex.printStackTrace();
          }
        }));
      }
    });
  }

  private void findAndDisplayImages(String currentNatureCameraId) {
    if (currentNatureCameraId == null) {
      return;
    }
    stopListing.set(false);
    listIsLoading.set(true);
    var queryParameters = new QueryParameters();
    queryParameters.setWhereClause("NatureCameraID='{" + currentNatureCameraId + "}'");
    var r = imageFeatureTable.queryFeaturesAsync(queryParameters);
    try {
      FeatureQueryResult res = r.get();
      var it = res.iterator();
      while (it.hasNext()) {
        var rr = it.next();
        if (shutdownNow.get() || stopListing.get()) {
          System.out.println("stopped");
          break;
        }
        ArcGISFeature f = (ArcGISFeature) rr;
        try {
          var attachmentList = f.fetchAttachmentsAsync().get();
          if (!attachmentList.isEmpty()) {
            var imageAttachment = attachmentList.get(0);
            if (shutdownNow.get() || stopListing.get()) {
              break;
            }

            ListenableFuture<InputStream> attachmentDataFuture = imageAttachment.fetchDataAsync();
            // listen for fetch data to complete
            attachmentDataFuture.addDoneListener(() -> {

              // get the attachments data as an input stream
              try {
                if (imageAttachment.hasFetchedData() && !stopListing.get()) {
                  InputStream attachmentInputStream = attachmentDataFuture.get();
                  // save the input stream to a temporary directory and get a reference to its URI
                  Image imageFromStream = new Image(attachmentInputStream);
                  attachmentInputStream.close();

                  Platform.runLater(() -> {
                    imageCount.set(imageCount.get() + 1);
                    GregorianCalendar cal = (GregorianCalendar) f.getAttributes().get("ImageDate");
                    String date = imageDateFormat.format(cal.getTime());
                    imagesList.add(new ImageRecord(date, imageFromStream));
                  });
                } else {
                  System.out.println("Couldn't fetch data for " + f.getAttributes().get("OBJECTID"));
                }
              } catch (Exception exception) {
                exception.printStackTrace();
                new Alert(Alert.AlertType.ERROR, "Error getting attachment").show();
              }
            });
          }
        } catch (InterruptedException | ExecutionException e) {
          System.out.println(e.getMessage());
          e.printStackTrace();
        }
      }
    } catch (InterruptedException | ExecutionException e) {
      System.out.println(e.getMessage());
      e.printStackTrace();
    }
    listIsLoading.set(false);
  }

  /**
   * Stops and releases all resources used in application.
   */
  @Override
  public void stop() {
    shutdownNow.set(true);
    System.out.println("Shutdown service");
    executorService.shutdown();
    mapView.dispose();
  }

  static class ImageCellFactory implements Callback<ListView<ImageRecord>, ListCell<ImageRecord>> {

    @Override
    public ListCell<ImageRecord> call(ListView<ImageRecord> param) {

      return new ListCell<>(){
        @Override
        public void updateItem(ImageRecord imageRecord, boolean empty) {
          super.updateItem(imageRecord, empty);
          if (empty || imageRecord == null) {
            setText(null);
            setGraphic(null);
          } else {
            setText(imageRecord.dateString);
            var iv = new ImageView(imageRecord.imageData());
            iv.fitWidthProperty().bind(imageListView.widthProperty().subtract(35));
            iv.setPreserveRatio(true);
            setGraphic(iv);
            setContentDisplay(ContentDisplay.TOP);
          }
          setStyle("-fx-border-color: gray; -fx-border-width: 1px; -fx-alignment: center; -fx-border-insets: 1px;");
        }
      };
    }
  }
}
