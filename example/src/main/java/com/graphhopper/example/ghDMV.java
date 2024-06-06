package com.graphhopper.example;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.AllEdgesIterator;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.details.PathDetail;
import com.opencsv.CSVWriter;

import java.io.*;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ghDMV {
    public static void main(String[] args) {
        String relDir = args.length == 1 ? args[0] : "";
        GraphHopper hopper = createGraphHopperInstance(relDir + "./data/dc-baltimore_maryland_bbbike2.osm.pbf");
        routing(hopper);
        hopper.close();
    }

    static GraphHopper createGraphHopperInstance(String ghLoc) {
        GraphHopper hopper = new GraphHopper();
        hopper.setOSMFile(ghLoc);

        // specify where to store graphhopper files
        hopper.setGraphHopperLocation("./data/dmv_gh");

        // see docs/core/profiles.md to learn more about profiles
        hopper.setProfiles(new Profile("car").setVehicle("car").setTurnCosts(false));

        hopper.setEncodedValuesString("osm_way_id");

        // this enables speed mode for the profile we called car
        hopper.getCHPreparationHandler().setCHProfiles(new CHProfile("car"));

        // now this can take minutes if it imports or a few seconds for loading of course this is dependent on the area you import
        hopper.importOrLoad();

        return hopper;
    }

    public static <string> void routing(GraphHopper hopper) {
        EncodingManager encodingManager = hopper.getEncodingManager();
        IntEncodedValue osmIDEnc = encodingManager.getIntEncodedValue(OSMWayID.KEY);
        EnumEncodedValue roadClas = encodingManager.getEnumEncodedValue(RoadClass.KEY, RoadClass.class);

        Graph graph = hopper.getBaseGraph();
        AllEdgesIterator allEdges = graph.getAllEdges();
        DecimalEncodedValue avSpeedEnc = hopper.getEncodingManager().getDecimalEncodedValue(VehicleSpeed.key("car"));

        // get information about the network. speed, length, road class, osmid of each edge
        File edge_file = new File("./data/GH_edges_osmid.csv");
        try{
            CSVWriter edge_writer = new CSVWriter(new FileWriter(edge_file));
            String[] edge_out_header = {"edges","length","osm_id","type","speed_ms"};

            edge_writer.writeNext(edge_out_header);
            while (allEdges.next()) {
                int alledgeId = allEdges.getEdge();
                double edge_len = allEdges.getDistance();
                double osm_ids = allEdges.get(osmIDEnc);
                Object class_val = allEdges.get(roadClas);
                double speed = allEdges.get(avSpeedEnc) * 0.277778;
                String[] edge_segment = {String.valueOf(alledgeId), String.valueOf(edge_len), String.valueOf(osm_ids), String.valueOf(class_val), String.valueOf(speed)};
                edge_writer.writeNext(edge_segment);
            }
            edge_writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // read the csv file and find the fastest path for each trip
        try (BufferedReader csvReader = new BufferedReader(new FileReader("./data/dataforGH.csv"))) {
            File route_file  = new File("./data/alledgesTime.csv");
            File route_file2 = new File("./data/alledges.csv");
            File route_file3 = new File("./data/alledgesID.csv");

            try {
                // create CSVWriter object filewriter object as parameter
                CSVWriter route_writer = new CSVWriter(new FileWriter(route_file));
                CSVWriter route_writer2 = new CSVWriter(new FileWriter(route_file2));
                CSVWriter route_writer3 = new CSVWriter(new FileWriter(route_file3));

                String[] out_header = {"agents", "time", "departure_time_sec","hr"};
                String[] out_header2 = {"agents", "departure_time_sec","distance"};
                String[] out_header3 = {"agents","edge_id", "departure_time_sec"};

                route_writer.writeNext(out_header);
                route_writer2.writeNext(out_header2);
                route_writer3.writeNext(out_header3);
                String[] segment = {};

                String header = csvReader.readLine();
                String row = null;
                int count = 0;
                System.out.println("start time is : "+new Timestamp(System.currentTimeMillis()));

                while ((row = csvReader.readLine()) != null) {
                    count++;
                    if (count % 10000 == 0) System.out.println("count.............." + count);

                    String[] data = row.split(",");
                    try {
                        GHRequest req = new GHRequest(Float.parseFloat(data[2]),
                                Float.parseFloat(data[1]),
                                Float.parseFloat(data[4]),
                                Float.parseFloat(data[3])).
                                // note that we have to specify which profile we are using even when there is only one like here
                                        setProfile("car").
                                setPathDetails(Arrays.asList("osm_way_id","edge_id","distance","time")).
                                // define the language for the turn instructions
                                        setLocale(Locale.US);
                        GHResponse rsp = hopper.route(req);

                        // handle errors
                        if (rsp.hasErrors())
                            throw new RuntimeException(rsp.getErrors().toString());

                        // use the best path, see the GHResponse class for more possibilities.
                        ResponsePath path = rsp.getBest();

                        Map<String, List<PathDetail>> details = path.getPathDetails();

                        for (PathDetail i : details.get("time")) {
                            Object time = i.getValue();
                            segment = new String[]{data[0], String.valueOf(time), data[6], data[5]};
                            route_writer.writeNext(segment);
                        }

                        for (PathDetail i : details.get("distance")) {
                            Object len = i.getValue();
                            segment = new String[]{data[0], data[6], String.valueOf(len)};
                            route_writer2.writeNext(segment);
                        }

                        for (PathDetail i : details.get("edge_id")) {
                            Object edge_id = i.getValue();
                            segment = new String[]{data[0], String.valueOf(edge_id), data[6]};
                            route_writer3.writeNext(segment);
                        }
                    }
                    catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                }

                // closing writer connection
                route_writer.close();
                route_writer2.close();
                route_writer3.close();

            }
            catch (IOException e) {
                e.printStackTrace();
            }
            csvReader.close();
        }

        catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("end time is : "+new Timestamp(System.currentTimeMillis()));
    }
}
