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
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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

  // A record to hold the data used in the list view cells
  private record ImageRecord(String dateString, Image imageData) {
  }

  private static final String serviceTableURL = "https://services1.arcgis.com/6677msI40mnLuuLr/ArcGIS/rest/services/NatureCamera/FeatureServer/0";
  private static final String imageTableURL = "https://services1.arcgis.com/6677msI40mnLuuLr/ArcGIS/rest/services/NatureCamera/FeatureServer/1";
  private ServiceFeatureTable cameraLocationFeatureTable;
  private ServiceFeatureTable imageFeatureTable;

  private MapView mapView;
  private ArcGISMap map;
  private final TextFlow titleTextArea = new TextFlow();
    // Display a count of images loaded
  private final TextField imageCountText = new TextField();

  private ListenableFuture<IdentifyLayerResult> identifyGraphics;

  // Manage a list of loaded images
  // Allow the thread loading the images to be told to bail out
  private final AtomicBoolean stopListing = new AtomicBoolean();
  private final IntegerProperty imageCount = new SimpleIntegerProperty();
  private static final ListView<ImageRecord> imageListView = new ListView<>();
  private final ObservableList<ImageRecord> imagesList = FXCollections.observableArrayList();
  private final BooleanProperty listIsLoading = new SimpleBooleanProperty();

  // Allow the loading of the images and the populating of the title area to run on threads.
  private final ExecutorService executorService = Executors.newFixedThreadPool(2);

  // The date string is used to sort the list, so if the formatting is modified might need to change sorting
  private static final SimpleDateFormat imageDateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.sss");

  // Some static text for use in the title area
  private static final Text noCamera = new Text("No camera selected");
  public static final Text cameraLabel = new Text("Camera name: ");
  public static final Text globalIdLabel = new Text("Global id: ");
  public static final Text objectIdLabel = new Text("ObjectId: ");

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

    ArcGISRuntimeEnvironment.setApiKey(
        "AAPK2967d54657e342d398c129c8581b516686jXNGXVc6CFPZVRVIFV6YbjpO_fn7fz4S2ECOR2nkZkZXxyfl51Iy8AS6h3qcXV");

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

    // Initialise map and MapView
    mapView = new MapView();

    map = new ArcGISMap(BasemapStyle.ARCGIS_STREETS);
    map.getOperationalLayers().add(featureLayer);
    mapView.setMap(map);
    map.loadAsync();
    featureLayer.loadAsync();
    featureLayer.addDoneLoadingListener(() -> mapView.setViewpoint(
        new Viewpoint(featureLayer.getFullExtent().getCenter(), 10000)));

    // Initialise the list view
    SortedList<ImageRecord> sortedList = new SortedList<>(imagesList, compareImageRecords);
    imageListView.setItems(sortedList);
    imageListView.setCellFactory(new ImageCellFactory());

    // Set up a split pane with map on left and box on right
    SplitPane splitPane = new SplitPane();
    splitPane.getItems().addAll(mapView, constructRightHandBox());
    Scene scene = new Scene(splitPane);
    stage.setScene(scene);

    mapView.setOnMouseClicked(e -> {
      if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
        // create a point from location clicked
        Point2D mapViewPoint = new Point2D(e.getX(), e.getY());

        // identify possible camera feature on the feature layer
        identifyGraphics = mapView.identifyLayerAsync(featureLayer, mapViewPoint, 10, false);
        identifyGraphics.addDoneListener(() -> executorService.execute(this::identifyAnySelectedCamera));
      }
    });
  }

  /**
   * A box with a title text area, a text area for the number of images, and a list of images
   * @return a VBox
   */
  private Region constructRightHandBox() {
    VBox rightHandBox = new VBox();
    rightHandBox.getChildren().addAll(titleTextArea, imageCountText, imageListView);
    // Want the list view to always fill remaining space
    VBox.setVgrow(imageListView, Priority.ALWAYS);

    // Initialise the title area
    noCamera.setFont(new Font(20));
    titleTextArea.getChildren().add(noCamera);
    titleTextArea.setPrefHeight(75);

    // Show an image count (and while the list is still loading add some indicator text)
    imageCountText.textProperty().bind(
        Bindings.concat("Image count: ",
            imageCount.asString(),
            Bindings.when(listIsLoading).then("   (List is loading)").otherwise("")));
    imageCount.set(0);
    return rightHandBox;
  }

  /**
   * Identify if a camera has been selected and if it has, start loading images for the selected camera.
   */
  private void identifyAnySelectedCamera() {
    try {
      var res = identifyGraphics.get(15, TimeUnit.SECONDS);
      Platform.runLater(() -> {
        imageCount.set(0);
        stopListing.set(true);
        imagesList.clear();
        titleTextArea.getChildren().clear();
      });

      if (!res.getElements().isEmpty()) {
        // A camera has been selected. Set up the title area and start loading images.
        // If multiple cameras are found we just load for the first in the list.
        var geoElement = res.getElements().get(0);
        var currentNatureCameraId = geoElement.getAttributes().get("GlobalId").toString();
        // Start loading images on separate thread.
        executorService.execute(() -> findAndDisplayImages(currentNatureCameraId));

        // Set up the title area text.
        Text cameraName = new Text(geoElement.getAttributes().get("CameraName").toString());
        cameraName.setFont(new Font(20));
        cameraName.setUnderline(true);
        cameraName.setTextAlignment(TextAlignment.CENTER);
        Text globalId = new Text(geoElement.getAttributes().get("GlobalID").toString());
        Text objectId = new Text(geoElement.getAttributes().get("ObjectId").toString());

        // Display title area text
        Platform.runLater(() -> titleTextArea.getChildren().addAll(
            cameraLabel, cameraName, new Text("\n"),
            globalIdLabel, globalId, new Text("\n"),
            objectIdLabel, objectId, new Text("\n")));
      } else {
        Platform.runLater(() -> titleTextArea.getChildren().add(noCamera));
      }
    } catch (InterruptedException | ExecutionException | TimeoutException ex) {
      System.out.println(ex.getMessage());
      ex.printStackTrace();
    }

  }

  /**
   * Load and display all images for the given camera.
   *
   * @param currentNatureCameraId the id for the camera to load images for
   */
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
      FeatureQueryResult res = r.get(20, TimeUnit.SECONDS);
      var it = res.iterator();
      while (it.hasNext()) {
        var rr = it.next();
        if (stopListing.get()) {
          System.out.println("stopped");
          break;
        }
        ArcGISFeature f = (ArcGISFeature) rr;
        try {
          var attachmentList = f.fetchAttachmentsAsync().get();
          if (!attachmentList.isEmpty()) {
            var imageAttachment = attachmentList.get(0);
            if (stopListing.get()) {
              break;
            }

            ListenableFuture<InputStream> attachmentDataFuture = imageAttachment.fetchDataAsync();
            // listen for fetch data to complete
            attachmentDataFuture.addDoneListener(() -> {

              // get the attachments data as an input stream
              try {
                if (stopListing.get()) {
                  return;
                }
                if (imageAttachment.hasFetchedData() && !stopListing.get()) {
                  InputStream attachmentInputStream = attachmentDataFuture.get(15, TimeUnit.SECONDS);
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
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
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
    // Stop loading any list
    stopListing.set(true);
    // Stop threads
    executorService.shutdown();
    // Dispose map view
    mapView.dispose();
  }

  /**
   * A cell factory to display an ImageRecord in a list.
   */
  private static class ImageCellFactory implements Callback<ListView<ImageRecord>, ListCell<ImageRecord>> {

    @Override
    public ListCell<ImageRecord> call(ListView<ImageRecord> param) {

      return new ListCell<>() {
        @Override
        public void updateItem(ImageRecord imageRecord, boolean empty) {
          super.updateItem(imageRecord, empty);
          if (empty || imageRecord == null) {
            setText(null);
            setGraphic(null);
          } else {
            setText(imageRecord.dateString);
            var imageView = new ImageView(imageRecord.imageData());
            // Want the image to almost fill the list width and to automatically adjust when list width changes
            imageView.fitWidthProperty().bind(imageListView.widthProperty().subtract(35));
            imageView.setPreserveRatio(true);
            setGraphic(imageView);
            // Image on top, date underneath
            setContentDisplay(ContentDisplay.TOP);
          }
          setStyle("-fx-border-color: gray; -fx-border-width: 1px; -fx-alignment: center; -fx-border-insets: 1px;");
        }
      };
    }
  }

  /**
   * Compare two image records based on their dates (fall back to comparing hashcodes is dates identical).
   */
  private final Comparator<ImageRecord> compareImageRecords = (o1, o2) -> {
    if (o1 == null && o2 == null) {
      return 0;
    } else if (o1 == null) {
      return -1;
    } else if (o2 == null) {
      return 1;
    } else if (o1.dateString.equals(o2.dateString)) {
      return o1.hashCode() - o2.hashCode();
    }
    return o1.dateString.compareTo(o2.dateString);
  };
}
