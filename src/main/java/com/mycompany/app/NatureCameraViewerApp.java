/**
 * Copyright 2024 Esri
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
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
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Callback;

public class NatureCameraViewerApp extends Application {

  record  ImageRecord (String dateString, Image imageData) {}

  private String natureCameraId = null;
  private static final String serviceTableURL = "https://services1.arcgis.com/6677msI40mnLuuLr/ArcGIS/rest/services/NatureCamera/FeatureServer/0";
  private static final String imageTableURL = "https://services1.arcgis.com/6677msI40mnLuuLr/ArcGIS/rest/services/NatureCamera/FeatureServer/1";
  private ServiceFeatureTable cameraLocationFeatureTable;

  private MapView mapView;

  private ArcGISMap map;

  private final TextArea textArea = new TextArea();
  private final TextField imageCountText = new TextField();

  private final ExecutorService executorService = Executors.newSingleThreadExecutor();

  private final AtomicBoolean shutdownNow = new AtomicBoolean();
  private final AtomicBoolean stopListing = new AtomicBoolean();
  private final IntegerProperty imageCount =new SimpleIntegerProperty();

  private ListenableFuture<IdentifyLayerResult> identifyGraphics;

  private ServiceFeatureTable imageFeatureTable;

  private final ListView<ImageRecord> imageListView = new ListView<>();

  private static final SimpleDateFormat imageDateFormat = new SimpleDateFormat("yyyy-MMM-dd hh:mm:ss");


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
    stage.setTitle("Nature camera app");
    stage.setWidth(800);
    stage.setHeight(600);
    stage.show();



    // connect to cameraLocationFeatureTable we will be recording imageListView into
    cameraLocationFeatureTable = new ServiceFeatureTable(serviceTableURL);
    cameraLocationFeatureTable.loadAsync();


    FeatureLayer featureLayer = new FeatureLayer(cameraLocationFeatureTable);
    mapView = new MapView();

    map = new ArcGISMap(BasemapStyle.ARCGIS_STREETS);


    map.getOperationalLayers().add(featureLayer);
    featureLayer.loadAsync();
    featureLayer.addDoneLoadingListener(() -> mapView.setViewpoint(new Viewpoint(featureLayer.getFullExtent())));
    mapView.setMap(map);
    map.loadAsync();



    // create a JavaFX scene with a stack pane as the root node and add it to the scene
    BorderPane borderPane = new BorderPane();
    borderPane.setCenter(mapView);
    mapView.minWidth(300);
    VBox vbox = new VBox();

    imageListView.setCellFactory(new ImageCellFactory());

    imageCountText.textProperty().bind(imageCount.asString());

    vbox.getChildren().addAll(textArea, imageCountText, imageListView);
    textArea.setText("No camera selected");
    imageCount.set(0);
    borderPane.setRight(vbox);
    Scene scene = new Scene(borderPane);
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
            System.out.println("Found " + res.getElements().size());
            natureCameraId = null;
            imageCount.set(0);
            imageListView.getItems().clear();
            stopListing.set(true);
            if (!res.getElements().isEmpty()) {
              var ele = res.getElements().get(0);
//                  ele.getAttributes().forEach((k,v) -> System.out.printf("ele: %s=%s\n", k, v));
              natureCameraId = ele.getAttributes().get("GlobalId").toString();
              executorService.execute(this::findImages);
              textArea.setText(String.format("%s\n%s\n%s",
                  ele.getAttributes().get("CameraName"),
                  ele.getAttributes().get("GlobalID"),
                  ele.getAttributes().get("OBJECTID")));
            } else {
              textArea.setText("No camera selected");
            }
          } catch (InterruptedException | ExecutionException ex) {
            System.out.println(ex.getMessage());
            ex.printStackTrace();
          }
        }));
      }
    });


    imageFeatureTable = new ServiceFeatureTable(imageTableURL);
    imageFeatureTable.addDoneLoadingListener(() -> {
      System.out.println("loaded image layer");
      imageFeatureTable.getFields().forEach(f -> System.out.println("field: " + f.getName() + "=" + f.toJson()));
    });
    imageFeatureTable.loadAsync();


  }

  void findImages() {
    stopListing.set(false);
    var q = new QueryParameters();
    System.out.println("Find imageListView for " + natureCameraId);
    q.setWhereClause("NatureCameraID='{" + natureCameraId + "}'");
//    q.setWhereClause("1=1");
    var r = imageFeatureTable.queryFeaturesAsync(q);
    try {
      System.out.println("query returned");
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
                    imageListView.getItems().add(new ImageRecord(date, imageFromStream));
                  });
                } else {
                  System.out.println("didn't fetch data for " + f.getAttributes().get("OBJECTID"));
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
            iv.setFitWidth(200);
            iv.setPreserveRatio(true);
            setGraphic(iv);
            setContentDisplay(ContentDisplay.TOP);
          }
        }
      };
    }
  }
}
