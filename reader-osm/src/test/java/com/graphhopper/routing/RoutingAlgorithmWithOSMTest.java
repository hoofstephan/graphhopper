/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.LMProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.reader.PrincetonReader;
import com.graphhopper.reader.dem.SRTMProvider;
import com.graphhopper.routing.ch.CHRoutingAlgorithmFactory;
import com.graphhopper.routing.ch.PrepareContractionHierarchies;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.querygraph.QueryRoutingCHGraph;
import com.graphhopper.routing.util.*;
import com.graphhopper.routing.util.TestAlgoCollector.AlgoHelperEntry;
import com.graphhopper.routing.util.TestAlgoCollector.OneRun;
import com.graphhopper.routing.weighting.ShortestWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.*;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.LocationIndexTree;
import com.graphhopper.storage.index.Snap;
import com.graphhopper.util.*;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import static com.graphhopper.GraphHopperTest.DIR;
import static com.graphhopper.util.Parameters.Algorithms.*;
import static com.graphhopper.util.Parameters.Routing.ALGORITHM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Try algorithms, indices and graph storages with real data
 *
 * @author Peter Karich
 */
public class RoutingAlgorithmWithOSMTest {
    TestAlgoCollector testCollector;

    public static List<AlgoHelperEntry> createAlgos(final GraphHopper hopper, Profile profile) {
        final GraphHopperStorage ghStorage = hopper.getGraphHopperStorage();
        LocationIndex idx = hopper.getLocationIndex();

        TraversalMode tMode = profile.isTurnCosts() ? TraversalMode.EDGE_BASED : TraversalMode.NODE_BASED;
        String addStr = "";
        if (profile.isTurnCosts())
            addStr = "turn|";

        Weighting weighting = hopper.createWeighting(profile, new PMap());
        PMap defaultHints = new PMap()
                .putObject(Parameters.CH.DISABLE, true)
                .putObject(Parameters.Landmark.DISABLE, true)
                .putObject("vehicle", profile.getVehicle())
                .putObject("weighting", profile.getWeighting());

        AlgorithmOptions defaultOpts = AlgorithmOptions.start(new AlgorithmOptions("", weighting, tMode)).hints(defaultHints).build();
        List<AlgoHelperEntry> algos = new ArrayList<>();
        algos.add(new AlgoHelperEntry(ghStorage, false, AlgorithmOptions.start(defaultOpts).algorithm(ASTAR).build(), idx, "astar|beeline|" + addStr + weighting));
        // later: include dijkstraOneToMany
        algos.add(new AlgoHelperEntry(ghStorage, false, AlgorithmOptions.start(defaultOpts).algorithm(DIJKSTRA).build(), idx, "dijkstra|" + addStr + weighting));

        AlgorithmOptions astarbiOpts = AlgorithmOptions.start(defaultOpts).algorithm(ASTAR_BI).build();
        astarbiOpts.getHints().putObject(ASTAR_BI + ".approximation", "BeelineSimplification");
        AlgorithmOptions dijkstrabiOpts = AlgorithmOptions.start(defaultOpts).algorithm(DIJKSTRA_BI).build();
        algos.add(new AlgoHelperEntry(ghStorage, false, astarbiOpts, idx, "astarbi|beeline|" + addStr + weighting));
        algos.add(new AlgoHelperEntry(ghStorage, false, dijkstrabiOpts, idx, "dijkstrabi|" + addStr + weighting));

        // add additional preparations if CH and LM preparation are enabled
        if (hopper.getLMPreparationHandler().isEnabled()) {
            final PMap lmHints = new PMap(defaultHints).putObject(Parameters.Landmark.DISABLE, false);
            final AlgorithmOptions opts = AlgorithmOptions.start(astarbiOpts).hints(lmHints).build();
            algos.add(new AlgoHelperEntry(ghStorage, false, opts, idx, "astarbi|landmarks|" + weighting) {
                @Override
                public RoutingAlgorithm createAlgo(Graph graph) {
                    return hopper.getLMPreparationHandler().getPreparation(profile.getName()).getRoutingAlgorithmFactory().createAlgo(graph, opts);
                }
            });
        }

        if (hopper.getCHPreparationHandler().isEnabled()) {
            final PMap chHints = new PMap(defaultHints);
            chHints.putObject(Parameters.CH.DISABLE, false);
            chHints.putObject(Parameters.Routing.EDGE_BASED, tMode.isEdgeBased());
            final AlgorithmOptions dijkstraOpts = AlgorithmOptions.start(dijkstrabiOpts).hints(chHints).build();
            algos.add(new AlgoHelperEntry(ghStorage, true, dijkstraOpts, idx, "dijkstrabi|ch|prepare|" + profile.getWeighting()) {
                @Override
                public RoutingAlgorithm createAlgo(Graph g) {
                    PrepareContractionHierarchies pch = hopper.getCHPreparationHandler().getPreparation(profile.getName());
                    RoutingCHGraph routingCHGraph = ghStorage.getRoutingCHGraph(pch.getCHConfig().getName());
                    if (g instanceof QueryGraph)
                        routingCHGraph = new QueryRoutingCHGraph(routingCHGraph, (QueryGraph) g);
                    return new CHRoutingAlgorithmFactory(routingCHGraph).createAlgo(new PMap().putObject(ALGORITHM, DIJKSTRA_BI));
                }
            });

            final AlgorithmOptions astarOpts = AlgorithmOptions.start(astarbiOpts).hints(chHints).build();
            algos.add(new AlgoHelperEntry(ghStorage, true, astarOpts, idx, "astarbi|ch|prepare|" + profile.getWeighting()) {
                public RoutingAlgorithm createAlgo(Graph g) {
                    PrepareContractionHierarchies pch = hopper.getCHPreparationHandler().getPreparation(profile.getName());
                    RoutingCHGraph routingCHGraph = ghStorage.getRoutingCHGraph(pch.getCHConfig().getName());
                    if (g instanceof QueryGraph)
                        routingCHGraph = new QueryRoutingCHGraph(routingCHGraph, (QueryGraph) g);
                    return new CHRoutingAlgorithmFactory(routingCHGraph).createAlgo(new PMap().putObject(ALGORITHM, ASTAR_BI));
                }
            });
        }

        return algos;
    }

    @Before
    public void setUp() {
        testCollector = new TestAlgoCollector("core integration tests");
    }

    List<OneRun> createMonacoCar() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(43.730729, 7.42135, 43.727697, 7.419199, 2580, 110));
        list.add(new OneRun(43.727687, 7.418737, 43.74958, 7.436566, 3588, 170));
        list.add(new OneRun(43.728677, 7.41016, 43.739213, 7.4277, 2561, 133));
        list.add(new OneRun(43.733802, 7.413433, 43.739662, 7.424355, 2230, 137));
        list.add(new OneRun(43.730949, 7.412338, 43.739643, 7.424542, 2100, 116));
        list.add(new OneRun(43.727592, 7.419333, 43.727712, 7.419333, 0, 1));

        // same special cases where GPS-exact routing could have problems (same edge and neighbor edges)
        list.add(new OneRun(43.727592, 7.419333, 43.727712, 7.41934, 0, 1));
        // on the same edge and very release
        list.add(new OneRun(43.727592, 7.419333, 43.727712, 7.4193, 3, 2));
        // one way stuff
        list.add(new OneRun(43.729445, 7.415063, 43.728856, 7.41472, 103, 4));
        list.add(new OneRun(43.728856, 7.41472, 43.729445, 7.415063, 320, 11));
        return list;
    }

    @Test
    public void testMonaco() {
        Graph g = runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                createMonacoCar(), true, false, new Profile("car").setVehicle("car").setWeighting("shortest"));

        assertEquals(testCollector.toString(), 0, testCollector.errors.size());

        // When OSM file stays unchanged make static edge and node IDs a requirement
        assertEquals(GHUtility.asSet(9, 111, 182), GHUtility.getNeighbors(g.createEdgeExplorer().setBaseNode(10)));
        assertEquals(GHUtility.asSet(19, 21), GHUtility.getNeighbors(g.createEdgeExplorer().setBaseNode(20)));
        assertEquals(GHUtility.asSet(478, 84, 83), GHUtility.getNeighbors(g.createEdgeExplorer().setBaseNode(480)));

        assertEquals(43.736989, g.getNodeAccess().getLat(10), 1e-6);
        assertEquals(7.429758, g.getNodeAccess().getLon(201), 1e-6);
    }

    @Test
    public void testMonacoMotorcycle() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(43.730729, 7.42135, 43.727697, 7.419199, 2682, 119));
        list.add(new OneRun(43.727687, 7.418737, 43.74958, 7.436566, 3728, 170));
        list.add(new OneRun(43.728677, 7.41016, 43.739213, 7.4277, 3168, 169));
        list.add(new OneRun(43.733802, 7.413433, 43.739662, 7.424355, 2423, 141));
        list.add(new OneRun(43.730949, 7.412338, 43.739643, 7.424542, 2253, 120));
        list.add(new OneRun(43.727592, 7.419333, 43.727712, 7.419333, 0, 1));
        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-mc-gh",
                list, true, true, new Profile("motorcycle").setVehicle("motorcycle").setWeighting("fastest"));

        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testMonacoMotorcycleCurvature() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(43.730729, 7.42135, 43.727697, 7.419199, 2681, 119));
        list.add(new OneRun(43.727687, 7.418737, 43.74958, 7.436566, 3727, 170));
        list.add(new OneRun(43.728677, 7.41016, 43.739213, 7.4277, 3168, 169));
        list.add(new OneRun(43.733802, 7.413433, 43.739662, 7.424355, 2423, 141));
        list.add(new OneRun(43.730949, 7.412338, 43.739643, 7.424542, 2253, 120));
        list.add(new OneRun(43.727592, 7.419333, 43.727712, 7.419333, 0, 1));
        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-mc-gh",
                list, true, true, new Profile("motorcycle").setVehicle("motorcycle").setWeighting("curvature"));

        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testBike2_issue432() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(52.349969, 8.013813, 52.349713, 8.013293, 56, 7));
        // reverse route avoids the location
//        list.add(new OneRun(52.349713, 8.013293, 52.349969, 8.013813, 293, 21));
        runAlgo(testCollector, DIR + "/map-bug432.osm.gz", "target/map-bug432-gh",
                list, true, true, new Profile("bike2").setVehicle("bike2").setWeighting("fastest"));

        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testOneWayCircleBug() {
        // export from http://www.openstreetmap.org/export#map=19/51.37605/-0.53155
        List<OneRun> list = new ArrayList<>();
        // going the bit longer way out of the circle
        list.add(new OneRun(51.376197, -0.531576, 51.376509, -0.530863, 153, 18));
        // now exacle the opposite direction: going into the circle (shorter)
        list.add(new OneRun(51.376509, -0.530863, 51.376197, -0.531576, 75, 15));

        runAlgo(testCollector, DIR + "/circle-bug.osm.gz", "target/circle-bug-gh",
                list, true, false, new Profile("car").setVehicle("car").setWeighting("shortest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testMoscow() {
        // extracted via ./graphhopper.sh extract "37.582641,55.805261,37.626929,55.824455"
        List<OneRun> list = new ArrayList<>();
        // choose perpendicular
        // http://localhost:8989/?point=55.818994%2C37.595354&point=55.819175%2C37.596931
        list.add(new OneRun(55.818994, 37.595354, 55.819175, 37.596931, 1052, 14));
        // should choose the closest road not the other one (opposite direction)
        // http://localhost:8989/?point=55.818898%2C37.59661&point=55.819066%2C37.596374
        list.add(new OneRun(55.818898, 37.59661, 55.819066, 37.596374, 24, 2));
        // respect one way!
        // http://localhost:8989/?point=55.819066%2C37.596374&point=55.818898%2C37.59661
        list.add(new OneRun(55.819066, 37.596374, 55.818898, 37.59661, 1114, 23));
        runAlgo(testCollector, DIR + "/moscow.osm.gz", "target/moscow-gh",
                list, true, false, new Profile("car").setVehicle("car").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testMoscowTurnCosts() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(55.813357, 37.5958585, 55.811042, 37.594689, 1043.99, 12));
        list.add(new OneRun(55.813159, 37.593884, 55.811278, 37.594217, 1048, 13));
        boolean testAlsoCH = true, is3D = false;
        runAlgo(testCollector, DIR + "/moscow.osm.gz", "target/graph-moscow",
                list, testAlsoCH, is3D, new Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(true));

        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testSimpleTurnCosts() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(-0.5, 0.0, 0.0, -0.5, 301015.98099, 6));
        boolean testAlsoCH = true, is3D = false;
        runAlgo(testCollector, DIR + "/test_simple_turncosts.osm.xml", "target/graph-simple_turncosts",
                list, testAlsoCH, is3D, new Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(true));

        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testSimplePTurn() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(0, 1, -1, 0, 667.08, 6));
        boolean testAlsoCH = true, is3D = false;
        runAlgo(testCollector, DIR + "/test_simple_pturn.osm.xml", "target/graph-simple_turncosts",
                list, testAlsoCH, is3D, new Profile("car").setVehicle("car").setWeighting("fastest").setTurnCosts(true));

        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testSidewalkNo() {
        List<OneRun> list = new ArrayList<>();
        // roundabout contains sidewalk=no which should be avoided
        list.add(new OneRun(57.154888, -2.101822, 57.153445, -2.099869, 329, 31));
        // longer path should go through tertiary, see discussion in #476
        list.add(new OneRun(57.154888, -2.101822, 57.147299, -2.096286, 1118, 68));

        boolean testAlsoCH = false, is3D = false;
        runAlgo(testCollector, DIR + "/map-sidewalk-no.osm.gz", "target/graph-sidewalkno",
                list, testAlsoCH, is3D, new Profile("hike").setVehicle("hike").setWeighting("fastest"));

        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testMonacoFastest() {
        List<OneRun> list = createMonacoCar();
        list.get(0).setLocs(1, 117);
        list.get(0).setDistance(1, 2584);
        list.get(3).setDistance(1, 2279);
        list.get(3).setLocs(1, 141);
        list.get(4).setDistance(1, 2149);
        list.get(4).setLocs(1, 120);
        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                list, true, false, new Profile("car").setVehicle("car").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testMonacoMixed() {
        // Additional locations are inserted because of new crossings from foot to highway paths!
        // Distance is the same.
        List<OneRun> list = createMonacoCar();
        list.get(0).setLocs(1, 110);
        list.get(1).setLocs(1, 170);
        list.get(2).setLocs(1, 132);
        list.get(3).setLocs(1, 137);
        list.get(4).setLocs(1, 116);

        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                list, false, false,
                new Profile("car").setVehicle("car").setWeighting("shortest"),
                new Profile("foot").setVehicle("foot").setWeighting("shortest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    List<OneRun> createMonacoFoot() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(43.730729, 7.421288, 43.727697, 7.419199, 1566, 92));
        list.add(new OneRun(43.727687, 7.418737, 43.74958, 7.436566, 3438, 136));
        list.add(new OneRun(43.728677, 7.41016, 43.739213, 7.427806, 2085, 112));
        list.add(new OneRun(43.733802, 7.413433, 43.739662, 7.424355, 1425, 89));
        return list;
    }

    @Test
    public void testMonacoFoot() {
        Graph g = runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                createMonacoFoot(), true, false, new Profile("foot").setVehicle("foot").setWeighting("shortest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());

        // see testMonaco for a similar ID test
        assertEquals(GHUtility.asSet(2, 909, 571), GHUtility.getNeighbors(g.createEdgeExplorer().setBaseNode(10)));
        assertEquals(GHUtility.asSet(444, 956, 740), GHUtility.getNeighbors(g.createEdgeExplorer().setBaseNode(441)));
        assertEquals(GHUtility.asSet(911, 404, 122, 914), GHUtility.getNeighbors(g.createEdgeExplorer().setBaseNode(912)));

        assertEquals(43.743705, g.getNodeAccess().getLat(100), 1e-6);
        assertEquals(7.426362, g.getNodeAccess().getLon(702), 1e-6);
    }

    @Test
    public void testMonacoFoot3D() {
        // most routes have same number of points as testMonaceFoot results but longer distance due to elevation difference
        List<OneRun> list = createMonacoFoot();
        list.get(0).setDistance(1, 1627);
        list.get(2).setDistance(1, 2250);
        list.get(3).setDistance(1, 1482);

        // or slightly longer tour with less nodes: list.get(1).setDistance(1, 3610);
        list.get(1).setDistance(1, 3573);
        list.get(1).setLocs(1, 149);

        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                list, true, true, new Profile("foot").setVehicle("foot").setWeighting("shortest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testNorthBayreuthHikeFastestAnd3D() {
        List<OneRun> list = new ArrayList<>();
        // prefer hiking route 'Teufelsloch Unterwaiz' and 'Rotmain-Wanderweg'        
        list.add(new OneRun(49.974972, 11.515657, 49.991022, 11.512299, 2384, 93));
        // prefer hiking route 'Markgrafenweg Bayreuth Kulmbach' but avoid tertiary highway from Pechgraben
        list.add(new OneRun(49.990967, 11.545258, 50.023182, 11.555386, 4746, 119));
        runAlgo(testCollector, DIR + "/north-bayreuth.osm.gz", "target/north-bayreuth-gh",
                list, true, true, new Profile("hike").setVehicle("hike").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testMonacoBike3D_twoSpeedsPerEdge() {
        List<OneRun> list = new ArrayList<>();
        // 1. alternative: go over steps 'Rampe Major' => 1.7km vs. around 2.7km
        list.add(new OneRun(43.730864, 7.420771, 43.727687, 7.418737, 2689, 118));
        // 2.
        list.add(new OneRun(43.728499, 7.417907, 43.74958, 7.436566, 3735, 194));
        // 3.
        list.add(new OneRun(43.728677, 7.41016, 43.739213, 7.427806, 2776, 167));
        // 4.
        list.add(new OneRun(43.733802, 7.413433, 43.739662, 7.424355, 1544, 84));

        // try reverse direction
        // 1.
        list.add(new OneRun(43.727687, 7.418737, 43.730864, 7.420771, 2599, 115));
        list.add(new OneRun(43.74958, 7.436566, 43.728499, 7.417907, 4180, 165));
        list.add(new OneRun(43.739213, 7.427806, 43.728677, 7.41016, 3244, 177));
        // 4. avoid tunnel(s)!
        list.add(new OneRun(43.739662, 7.424355, 43.733802, 7.413433, 2436, 112));
        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                list, true, true, new Profile("bike2").setVehicle("bike2").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testLandmarkBug() {
        List<OneRun> list = new ArrayList<>();
        OneRun run = new OneRun();
        run.add(50.016923, 11.514187, 0, 0);
        run.add(50.019129, 11.500325, 0, 0);
        run.add(50.023623, 11.56929, 7069, 178);
        list.add(run);

        runAlgo(testCollector, DIR + "/north-bayreuth.osm.gz", "target/north-bayreuth-gh",
                list, true, false, new Profile("bike").setVehicle("bike").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testBug1014() {
        List<OneRun> list = new ArrayList<>();
        OneRun run = new OneRun();
        run.add(50.015861, 11.51041, 0, 0);
        run.add(50.019129, 11.500325, 0, 0);
        run.add(50.023623, 11.56929, 6777, 175);
        list.add(run);

        runAlgo(testCollector, DIR + "/north-bayreuth.osm.gz", "target/north-bayreuth-gh",
                list, true, false, new Profile("bike").setVehicle("bike").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testMonacoBike() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(43.730864, 7.420771, 43.727687, 7.418737, 1642, 87));
        list.add(new OneRun(43.727687, 7.418737, 43.74958, 7.436566, 3580, 168));
        list.add(new OneRun(43.728677, 7.41016, 43.739213, 7.427806, 2323, 121));
        list.add(new OneRun(43.733802, 7.413433, 43.739662, 7.424355, 1434, 89));
        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                list, true, false, new Profile("bike").setVehicle("bike").setWeighting("shortest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testMonacoMountainBike() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(43.730864, 7.420771, 43.727687, 7.418737, 2322, 110));
        list.add(new OneRun(43.727687, 7.418737, 43.74958, 7.436566, 3655, 176));
        list.add(new OneRun(43.728677, 7.41016, 43.739213, 7.427806, 2331, 121));
        // hard to select between secondary and primary (both are AVOID for mtb)
        list.add(new OneRun(43.733802, 7.413433, 43.739662, 7.424355, 1459, 88));
        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                list, true, false, new Profile("mtb").setVehicle("mtb").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());

        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                list, false, false,
                new Profile("mtb").setVehicle("mtb").setWeighting("fastest"),
                new Profile("racingbike").setVehicle("racingbike").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testMonacoRacingBike() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(43.730864, 7.420771, 43.727687, 7.418737, 2594, 111));
        list.add(new OneRun(43.727687, 7.418737, 43.74958, 7.436566, 3588, 170));
        list.add(new OneRun(43.728677, 7.41016, 43.739213, 7.427806, 2572, 135));
        list.add(new OneRun(43.733802, 7.413433, 43.739662, 7.424355, 1490, 84));
        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                list, true, false, new Profile("racingbike").setVehicle("racingbike").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());

        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                list, false, false,
                new Profile("racingbike").setVehicle("racingbike").setWeighting("fastest"),
                new Profile("bike").setVehicle("bike").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testKremsBikeRelation() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(48.409523, 15.602394, 48.375466, 15.72916, 12491, 159));
        list.add(new OneRun(48.410061, 15.63951, 48.411386, 15.604899, 3077, 79));
        list.add(new OneRun(48.412294, 15.62007, 48.398306, 15.609667, 3965, 94));

        runAlgo(testCollector, DIR + "/krems.osm.gz", "target/krems-gh",
                list, true, false, new Profile("bike").setVehicle("bike").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());

        runAlgo(testCollector, DIR + "/krems.osm.gz", "target/krems-gh",
                list, false, false,
                new Profile("bike").setVehicle("bike").setWeighting("fastest"),
                new Profile("car").setVehicle("car").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testKremsMountainBikeRelation() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(48.409523, 15.602394, 48.375466, 15.72916, 12574, 169));
        list.add(new OneRun(48.410061, 15.63951, 48.411386, 15.604899, 3101, 94));
        list.add(new OneRun(48.412294, 15.62007, 48.398306, 15.609667, 3965, 95));

        runAlgo(testCollector, DIR + "/krems.osm.gz", "target/krems-gh",
                list, true, false, new Profile("mtb").setVehicle("mtb").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());

        runAlgo(testCollector, DIR + "/krems.osm.gz", "target/krems-gh",
                list, false, false,
                new Profile("mtb").setVehicle("mtb").setWeighting("fastest"),
                new Profile("bike").setVehicle("bike").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    List<OneRun> createAndorra() {
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(42.56819, 1.603231, 42.571034, 1.520662, 17708, 524));
        list.add(new OneRun(42.529176, 1.571302, 42.571034, 1.520662, 11408, 305));
        return list;
    }

    @Test
    public void testAndorra() {
        runAlgo(testCollector, DIR + "/andorra.osm.gz", "target/andorra-gh",
                createAndorra(), true, false, new Profile("car").setVehicle("car").setWeighting("shortest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testAndorraPbf() {
        runAlgo(testCollector, DIR + "/andorra.osm.pbf", "target/andorra-gh",
                createAndorra(), true, false, new Profile("car").setVehicle("car").setWeighting("shortest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testAndorraFoot() {
        List<OneRun> list = createAndorra();
        list.get(0).setDistance(1, 16354);
        list.get(0).setLocs(1, 648);
        list.get(1).setDistance(1, 12701);
        list.get(1).setLocs(1, 431);

        runAlgo(testCollector, DIR + "/andorra.osm.gz", "target/andorra-gh",
                list, true, false, new Profile("foot").setVehicle("foot").setWeighting("shortest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testCampoGrande() {
        // test not only NE quadrant of earth!

        // bzcat campo-grande.osm.bz2 
        //   | ./bin/osmosis --read-xml enableDateParsing=no file=- --bounding-box top=-20.4 left=-54.6 bottom=-20.6 right=-54.5 --write-xml file=- 
        //   | bzip2 > campo-grande.extracted.osm.bz2
        List<OneRun> list = new ArrayList<>();
        list.add(new OneRun(-20.4, -54.6, -20.6, -54.54, 25516, 271));
        list.add(new OneRun(-20.43, -54.54, -20.537, -54.674, 18009, 237));
        runAlgo(testCollector, DIR + "/campo-grande.osm.gz", "target/campo-grande-gh", list,
                false, false, new Profile("car").setVehicle("car").setWeighting("shortest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testMonacoVia() {
        OneRun oneRun = new OneRun();
        oneRun.add(43.730729, 7.42135, 0, 0);
        oneRun.add(43.727697, 7.419199, 2581, 110);
        oneRun.add(43.726387, 7.4, 3001, 90);

        List<OneRun> list = new ArrayList<>();
        list.add(oneRun);

        runAlgo(testCollector, DIR + "/monaco.osm.gz", "target/monaco-gh",
                list, true, false, new Profile("car").setVehicle("car").setWeighting("shortest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testHarsdorf() {
        List<OneRun> list = new ArrayList<>();
        // TODO somehow the bigger road is take even if we make it less preferred (e.g. introduce AVOID AT ALL costs for lanes=2&&maxspeed>50)
        list.add(new OneRun(50.004333, 11.600254, 50.044449, 11.543434, 6952, 190));

        // choose Unterloher Weg and the following residential + cycleway
        // list.add(new OneRun(50.004333, 11.600254, 50.044449, 11.543434, 6931, 184));
        runAlgo(testCollector, DIR + "/north-bayreuth.osm.gz", "target/north-bayreuth-gh",
                list, true, false, new Profile("bike").setVehicle("bike").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testNeudrossenfeld() {
        List<OneRun> list = new ArrayList<>();
        // choose cycleway (Dreschenauer Straße)
        list.add(new OneRun(49.987132, 11.510496, 50.018839, 11.505024, 3985, 106));

        runAlgo(testCollector, DIR + "/north-bayreuth.osm.gz", "target/north-bayreuth-gh",
                list, true, true, new Profile("bike").setVehicle("bike").setWeighting("fastest"));

        runAlgo(testCollector, DIR + "/north-bayreuth.osm.gz", "target/north-bayreuth-gh",
                list, true, true, new Profile("bike2").setVehicle("bike2").setWeighting("fastest"));
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
    }

    @Test
    public void testDisconnectedAreaAndMultiplePoints() {
        List<OneRun> list = new ArrayList<>();
        OneRun oneRun = new OneRun();
        oneRun.add(53.753177, 9.435968, 10, 10);
        oneRun.add(53.751299, 9.386959, 10, 10);
        oneRun.add(53.751299, 9.3869, 10, 10);
        list.add(oneRun);

        runAlgo(testCollector, DIR + "/krautsand.osm.gz", "target/krautsand-gh",
                list, true, true, new Profile("car").setVehicle("car").setWeighting("fastest"));
    }

    /**
     * @param withCH if true also the CH and LM algorithms will be tested which needs
     *               preparation and takes a bit longer
     */
    Graph runAlgo(TestAlgoCollector testCollector, String osmFile,
                  String graphFile, List<OneRun> runs, boolean withCH, boolean is3D, Profile... profiles) {

        // for different weightings we need a different storage, otherwise we would need to remove the graph folder
        // every time we come with a different weighting
        // graphFile += weightStr;

        AlgoHelperEntry algoEntry = null;
        OneRun tmpOneRun = null;
        try {
            Profile queryProfile = profiles[0];
            Helper.removeDir(new File(graphFile));
            String encodersString = Arrays.stream(profiles).map(p -> p.getVehicle() + (p.isTurnCosts() ? "|turn_costs=true" : "")).collect(Collectors.joining(","));
            EncodingManager em = EncodingManager.create(encodersString);
            GraphHopper hopper = new GraphHopper().
                    setStoreOnFlush(true).
                    setOSMFile(osmFile).
                    setProfiles(profiles).
                    setGraphHopperLocation(graphFile).
                    setEncodingManager(em);
            hopper.setMinNetworkSize(0);

            if (osmFile.contains("moscow"))
                hopper.setMinNetworkSize(200);
            // avoid that path.getDistance is too different to path.getPoint.calcDistance
            hopper.setWayPointMaxDistance(0);

            // always enable landmarks
            hopper.getLMPreparationHandler().
                    setLMProfiles(new LMProfile(queryProfile.getName()));

            if (withCH) {
                assert !Helper.isEmpty(queryProfile.getWeighting());
                hopper.getCHPreparationHandler().
                        setCHProfiles(new CHProfile(queryProfile.getName()));
            }

            if (is3D)
                hopper.setElevationProvider(new SRTMProvider(DIR));

            hopper.importOrLoad();

            Collection<AlgoHelperEntry> prepares = createAlgos(hopper, queryProfile);
            FlagEncoder encoder = hopper.getEncodingManager().getEncoder(queryProfile.getName());
            EdgeFilter edgeFilter = DefaultEdgeFilter.allEdges(encoder.getAccessEnc());
            for (AlgoHelperEntry entry : prepares) {
                if (entry.getExpectedAlgo().startsWith("astarbi|ch")) {
                    continue;
                }
                algoEntry = entry;
                LocationIndex idx = entry.getIdx();
                for (OneRun oneRun : runs) {
                    tmpOneRun = oneRun;
                    List<Snap> list = oneRun.getList(idx, edgeFilter);
                    testCollector.assertDistance(hopper.getEncodingManager(), algoEntry, list, oneRun);
                }
            }

            return hopper.getGraphHopperStorage();
        } catch (Exception ex) {
            if (algoEntry == null)
                throw new RuntimeException("cannot handle file " + osmFile + ", " + ex.getMessage(), ex);

            throw new RuntimeException("cannot handle " + algoEntry.toString() + ", for " + tmpOneRun
                    + ", file " + osmFile + ", " + ex.getMessage(), ex);
        } finally {
            // Helper.removeDir(new File(graphFile));
        }
    }

    @Test
    public void testMonacoParallel() {
        System.out.println("testMonacoParallel takes a bit time...");
        String graphFile = "target/monaco-gh";
        Helper.removeDir(new File(graphFile));
        final EncodingManager encodingManager = EncodingManager.create("car");
        final GraphHopper hopper = new GraphHopper().
                setStoreOnFlush(true).
                setEncodingManager(encodingManager).
                setWayPointMaxDistance(0).
                setOSMFile(DIR + "/monaco.osm.gz").
                setGraphHopperLocation(graphFile).
                setMinNetworkSize(0).
                setProfiles(new Profile("car").setVehicle("car").setWeighting("fastest")).
                importOrLoad();
        final Graph g = hopper.getGraphHopperStorage();
        final LocationIndex idx = hopper.getLocationIndex();
        final List<OneRun> instances = createMonacoCar();
        List<Thread> threads = new ArrayList<>();
        final AtomicInteger integ = new AtomicInteger(0);
        int MAX = 100;
        final FlagEncoder carEncoder = encodingManager.getEncoder("car");

        // testing if algorithms are independent. should be. so test only two algorithms. 
        // also the preparing is too costly to be called for every thread
        int algosLength = 2;
        final Weighting weighting = new ShortestWeighting(encodingManager.getEncoder("car"));
        final EdgeFilter filter = DefaultEdgeFilter.allEdges(carEncoder.getAccessEnc());
        for (int no = 0; no < MAX; no++) {
            for (int instanceNo = 0; instanceNo < instances.size(); instanceNo++) {
                String[] algos = new String[]{
                        ASTAR, DIJKSTRA_BI
                };
                for (final String algoStr : algos) {
                    // an algorithm is not thread safe! reuse via clear() is ONLY appropriated if used from same thread!
                    final int instanceIndex = instanceNo;
                    Thread t = new Thread() {
                        @Override
                        public void run() {
                            OneRun oneRun = instances.get(instanceIndex);
                            AlgorithmOptions opts = AlgorithmOptions.start().weighting(weighting).algorithm(algoStr).build();
                            testCollector.assertDistance(encodingManager, new AlgoHelperEntry(g, false, opts, idx, algoStr + "|" + weighting),
                                    oneRun.getList(idx, filter), oneRun);
                            integ.addAndGet(1);
                        }
                    };
                    t.start();
                    threads.add(t);
                }
            }
        }

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }

        assertEquals(MAX * algosLength * instances.size(), integ.get());
        assertEquals(testCollector.toString(), 0, testCollector.errors.size());
        hopper.close();
    }

    @Test
    public void testPerformance() throws IOException {
        int N = 10;
        int noJvmWarming = N / 4;

        Random rand = new Random(0);
        final EncodingManager eManager = EncodingManager.create("car");
        final GraphHopperStorage graph = new GraphBuilder(eManager).create();

        String bigFile = "10000EWD.txt.gz";
        new PrincetonReader(graph, eManager.getEncoder("car")).setStream(new GZIPInputStream(PrincetonReader.class.getResourceAsStream(bigFile))).read();
        GraphHopper hopper = new GraphHopper() {
            {
                setEncodingManager(eManager);
                loadGraph(graph);
            }

            @Override
            protected LocationIndex createLocationIndex(Directory dir) {
                return new LocationIndexTree(graph, dir);
            }
        };

        Collection<AlgoHelperEntry> prepares = createAlgos(hopper, new Profile("car").setVehicle("car").setWeighting("shortest"));

        for (AlgoHelperEntry entry : prepares) {
            StopWatch sw = new StopWatch();
            for (int i = 0; i < N; i++) {
                int node1 = Math.abs(rand.nextInt(graph.getNodes()));
                int node2 = Math.abs(rand.nextInt(graph.getNodes()));
                RoutingAlgorithm d = entry.createAlgo(graph);
                if (i >= noJvmWarming)
                    sw.start();

                Path p = d.calcPath(node1, node2);
                // avoid jvm optimization => call p.distance
                if (i >= noJvmWarming && p.getDistance() > -1)
                    sw.stop();

                // System.out.println("#" + i + " " + name + ":" + sw.getSeconds() + " " + p.nodes());
            }

            float perRun = sw.stop().getSeconds() / ((float) (N - noJvmWarming));
            System.out.println("# " + getClass().getSimpleName() + " " + entry
                    + ":" + sw.stop().getSeconds() + ", per run:" + perRun);
            assertTrue("speed too low!? " + perRun + " per run", perRun < 0.08);
        }
    }
}
