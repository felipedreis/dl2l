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

## Machine Learning — JEPA World Model

DL2L includes an offline-trained JEPA (Joint Embedding Predictive Architecture) world model that each creature uses during sleep consolidation to refine its action-selection policy. The model is trained from simulation trajectories extracted from PostgreSQL and exported as TorchScript artifacts loaded by the Java runtime via DJL.

### Model architectures

Four variants are available, differing in how the creature's homeostatic state (`h_t`) is routed:

| Variant | Predictor sees | Critic sees | Best val L_pred |
|---|---|---|---|
| `internal_critic` | `z_world` only | `z_next + z_internal` | **0.1683** |
| `single` | `z_world` only | `z_next` only | 0.1732 |
| `internal_predictor` | `z_world + z_internal` | `z_next` only | 0.1750 |
| `dual` | `z_world + z_internal` | `z_next + z_internal` | 0.1884 |

### Training data extraction

Simulation data is extracted from PostgreSQL using `scripts/pg_extract.py` (Python + `docker exec psql`, no psycopg2 required):

```bash
python3 scripts/pg_extract.py --out /path/to/output --container <db-container>
```

The script covers all creature-level and ensemble-level data: trajectories, sleep episodes, engrams, arousal history, behavioural efficiency, traveled distances, perception coverage, consolidation batch stats, and more.

After extraction, the ML dataset is assembled with:

```bash
cd ml
python3 -m scripts.prepare_dataset --wd /path/to/output --out data_p9 --dual
```

### Training and export

```bash
cd ml
# Train all four variants (50 epochs, MPS/CUDA/CPU):
for VARIANT in single dual internal_critic internal_predictor; do
    python3 -m scripts.train_species --data data_p9 --ckpt checkpoints_p9/$VARIANT \
        --epochs 50 --variant $VARIANT
done

# Export TorchScript artifacts:
for VARIANT in single dual internal_critic internal_predictor; do
    python3 -m scripts.export_model --variant $VARIANT \
        --ckpt checkpoints_p9/$VARIANT \
        --out src/main/resources/models/$VARIANT
done
```

### HuggingFace repositories

| Repository | Type | Contents |
|---|---|---|
| [`felipedreis/dl2l-jepa`](https://huggingface.co/felipedreis/dl2l-jepa) | Model | TorchScript `.pt` files + `model_contract.json` for all 4 variants |
| [`felipedreis/dl2l-experiments`](https://huggingface.co/datasets/felipedreis/dl2l-experiments) | Dataset | Parquet training/validation files + `stats.json`, organized by experiment (e.g. `p9/`) |

To upload models and dataset:

```bash
cd ml
python3 -m scripts.upload_hf \
    --repo felipedreis/dl2l-jepa \
    --data-repo felipedreis/dl2l-experiments \
    --ckpt checkpoints_p9 --data data_p9 --data-prefix p9
```
