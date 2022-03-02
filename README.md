# README #

## DL2L ##

### Summary ###

This is the repository for DL2L (Distributed - Live to learn, learn to live), an artificial life simulator based on the Artifice architecture and using the actors model as a foundation for concurrency and distribution. It was the subject of the undergrad final project (2017/2) by student Felipe Duarte dos Reis (felipedreis@cefetmg.br) under the guidance of Professor Dr. Henrique Elias Borges. The source code is predominantly Java, and uses the Akka actor model implementation.

## How to run DL2L ##

DL2L can either be run on the local machine for quick tests, or on a cluster to simulate long-running experiments. Using the local machine, only a single holder is allowed for now.

### Running on local machine ###

To run on the local machine it is necessary to change the akka.cluster.seed-nodes configuration in the configuration file located at src/main/resources/application.conf, changing the IP to localhost. You also need to change the src/main/resources/META-INF/persistence.xml file with the correct persistence engine information. When creating the database, be careful to create a schema named data. Once the necessary settings have been made, package the project with the `mvn package` command at the root of the project.

In the scripts directory there are four scripts that instantiate each of the simulation nodes, they are: manager.sh, provider.sh, detector.sh and holder.sh. Run them on different terminals in this order from the project root directory, e.g. in the tcc directory run ./scripts/manager.sh and so on. The holder.sh and manager.sh logs will inform you whether the simulation started successfully or not. The configuration executed will be the basic.conf that is in the simulations directory.

### Running on PPGMMC cluster ###

To run the simulations on the cluster, the same configuration files as in the previous item must be changed, taking care to put the real IP address of compute-0-34, where the manager will run it. Once the changes are made, it is necessary to run the copyToCluster.sh <user> command, which will package the project and copy the necessary dependencies to the user's directory passed by parameter. As several files are copied one-by-one, it is suggested to create an ssh key for your user, avoiding typing the password at each login.

Having finished copying, just access the PPGMMC cluster and run the deploy.sh script, passing the simulation configuration file and the number of repetitions as parameters.

### Data analysis ###

The holders, when they finish running, will store the simulation data as well as the backups in a folder whose name will be the process id in SLURM. This data must be compressed and copied back to the project directory for analysis.

The scripts for data analysis are in the analysis directory, at the root of the project. They were written in Python 2.7 using the numpy and scipy libraries. The main files are exp1.py, exp2.py, exp3.py and tracing.py. In each of them, the `wd` variable must be changed, which points to the directory where the simulation results are.


## How to contribute ##

The project's source code is neatly organized into a few packages. Are they:

* `analysis`: In this package are the classes responsible for executing the database queries, extracting, organizing and writing the data in a CSV file. There are two types of data, those concerning the sample (data from the set of creatures, e.g. nutrients eaten, distance traveled) and those concerning the dynamics of each creature;
* `cluster`: In this package are the classes that define the actors and control messages between the entities that make up the cluster. These entities are the SimulationManager, the IdProvider, the CollisionDetector, and the Holder. Each of these members has a clear role in the simulation and these are explained in the monograph;
* `common`: Contains classes that are useful for project development but are not part of its main objective, such as actor model extensions, data structures that complement the Java standard library, etc;
* `creature`: The actors that make up the artificial creature, as well as its subsystems, are in this package;
* `gui`: Package intended for GUI components;
* `physics`: Package for the physical representation of creatures and nutrients in the `CollisionDetector`, called `Geometry`, and the `PositioningAttributes`, classes used in `holders` to transmit location information;
* `stimuli`: The stimuli exchanged internally between the creature components and the stimuli exchanged with the artificial world are in this package;
* `world`: Entities from the artificial world such as nutrients and predators should be in this package.

Classes don't all follow the JavaBeans standard, this mainly thanks to the actors model. Messages exchanged between actors should be immutable, per a recommendation from _toolkit_ Akka
