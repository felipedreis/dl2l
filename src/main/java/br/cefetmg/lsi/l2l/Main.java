package br.cefetmg.lsi.l2l;

import akka.actor.*;
import akka.stream.Materializer;
import br.cefetmg.lsi.l2l.analysis.DataAnalyser;
import br.cefetmg.lsi.l2l.analysis.extractor.Extractor;
import br.cefetmg.lsi.l2l.cluster.Holder;
import br.cefetmg.lsi.l2l.cluster.SequentialIdProvider;
import br.cefetmg.lsi.l2l.cluster.SimulationManager;
import br.cefetmg.lsi.l2l.cluster.settings.Simulation;
import br.cefetmg.lsi.l2l.cluster.GUIActor;
import br.cefetmg.lsi.l2l.cluster.CollisionDetectorActor;
import br.cefetmg.lsi.l2l.metrics.MetricsExtension;
import br.cefetmg.lsi.l2l.web.GeometrySourceProvider;
import br.cefetmg.lsi.l2l.web.GeometryWebService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.cli.*;

import javax.persistence.EntityManager;
import javax.persistence.Persistence;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Created by felipe on 02/01/17.
 */
public class Main {

    private static Options options;
    private static Logger logger = Logger.getLogger(Main.class.getName());


    public static void main(String [] args) throws InterruptedException {
        try (InputStream cfg = Main.class.getResourceAsStream("/logging.properties")) {
            if (cfg != null) LogManager.getLogManager().readConfiguration(cfg);
        } catch (Exception ignored) {}

        CommandLineParser parser = new BasicParser();
        try {
            CommandLine commandLine = parser.parse(options, args);

            String host = commandLine.getOptionValue("host"),
                    port = commandLine.getOptionValue("port"),
                    saveDir =  commandLine.getOptionValue("save", "");

            if (commandLine.hasOption("extractor")) {
                logger.info("Running extractor in l2l database and exiting");
                runExtractor(saveDir);
                return;
            }

            System.out.println("Save dir " + saveDir);

            File configFile = new File(commandLine.getOptionValue("simulation"));
            String[] roles = Arrays.stream(commandLine.getOptionValues("roles"))
                    .flatMap(r -> Arrays.stream(r.split(",")))
                    .toArray(String[]::new);

            Simulation simulation = new Simulation(ConfigFactory.parseFile(configFile));

            String roleParam = roles[0];

            for(int i = 1; i < roles.length; ++i)
                roleParam += (", " + roles[i]);

            String simulationName = configFile.getName();
            String trialId = System.getenv().getOrDefault("TRIAL_ID", "local");

            Config config = ConfigFactory.parseString(
                    "akka.cluster.roles = [" + roleParam + "]\n" +
                    "dl2l.metrics.simulation-name = \"" + simulationName + "\"\n" +
                    "dl2l.metrics.trial-id = \"" + trialId + "\"")
                    .withFallback(ConfigFactory.load());

            ActorSystem system = ActorSystem.create("l2l", config);
            MetricsExtension.of(system); // starts the /metrics endpoint on every node, regardless of role

            logger.info("System started at host " + host + ":" + port + " with roles " + Arrays.toString(roles));

            for(String role : roles) {

                switch (role) {
                    case "idProvider":
                        setupIdProvider(system);
                        logger.info("Started idProvider");
                        break;

                    case "holder":
                        setupHolder(system, simulation, saveDir);
                        logger.info("Started holder");
                        break;

                    case "manager":
                        setupSimulationManager(system, simulation);
                        logger.info("Started manager");
                        break;

                    case "collisionDetector":
                        setupCollisionDetector(system, simulation);
                        logger.info("Started collision detector");
                }
                Thread.sleep(500);
            }
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    private static void runExtractor(String saveDir) {

        Map<String, String> properties = new HashMap<>();
        properties.put("eclipselink.ddl-generation", "none");
        String dbUrl = System.getenv("DL2L_DB_URL");
        if (dbUrl != null && !dbUrl.isEmpty()) {
            properties.put("javax.persistence.jdbc.url", dbUrl);
        }
        EntityManager em = Persistence.createEntityManagerFactory("L2LPU", properties).createEntityManager();
        DataAnalyser analyser = new DataAnalyser(em, saveDir);
        CompletableFuture future = analyser.run();
        future.join();
    }

    private static ActorRef setupCollisionDetector(ActorSystem system, Simulation settings) {
        Materializer materializer = Materializer.matFromSystem(system);
        GeometrySourceProvider provider = new GeometrySourceProvider(materializer);

        var collisionDetector = system.actorOf(Props.create(CollisionDetectorActor.class, settings, provider)
                .withDispatcher("collision-dispatcher"), "collisionDetector");

        var webService = new GeometryWebService(system, materializer, provider);
        webService.start("0.0.0.0", 8080);

        return collisionDetector;
    }

    private static ActorRef setupIdProvider(ActorSystem system) {
        return system.actorOf(Props.create(SequentialIdProvider.class), "idProvider");
    }

    private static ActorRef setupSimulationManager(ActorSystem system, Simulation settings) {
        return system.actorOf(Props.create(SimulationManager.class, settings), "manager");
    }

    private static ActorRef setupHolder(ActorSystem system, Simulation settings, String saveDir) {
        return system.actorOf(Props.create(Holder.class, settings, saveDir), "holder");
    }

    static {
        Option help = OptionBuilder.create("help");

        Option host = OptionBuilder.withArgName("ip")
                .hasArg()
                .isRequired()
                .create("host");

        Option port = OptionBuilder.withArgName("portNumber")
                .hasArg()
                .isRequired()
                .create("port");

        Option roles = OptionBuilder
                .hasArgs()
                .isRequired()
                .create("roles");

        Option simulation = OptionBuilder
                .hasArg()
                .create("simulation");

        Option save = OptionBuilder.withArgName("dataDir")
                .hasArg()
                .create("save");

        Option extractor = OptionBuilder.create("extractor");

        options = new Options();
        options.addOption(help);
        options.addOption(host);
        options.addOption(port);
        options.addOption(roles);
        options.addOption(save);
        options.addOption(simulation);
        options.addOption(extractor);
    }
}
