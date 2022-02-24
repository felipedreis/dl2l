# README #

## DL2L ##

### Sumário ###

Este é o repositório do DL2L (Distributed - Live to learn, learn to live), um simulador de vida artificial baseado na arquitetura Artífice e utiliza o modelo de atores como fundamento para concorrência e distribuição. Foi tema da defesa de TCC (2017/2) do aluno Felipe Duarte dos Reis (felipedreis@cefetmg.br) sob a orientação do professor Dr. Henrique Elias Borges. O código fonte é predominantemente Java, e utiliza a implementação do modelo de atores Akka.

## Como executar o DL2L ##

O DL2L pode ser tanto executado na máquina local para testes rápidos, ou em um cluster para simular experimentos de longa duração. Utilizando a máquina local, só é permitido um único holder por enquanto. 

### Executando na máquina local ###

Para executar na máquina local é necessário alterar a configuração akka.cluster.seed-nodes no arquivo de configuração localizado em src/main/resources/application.conf, mudando o IP para localhost. É necessário também alterar o arquivo src/main/resources/META-INF/persistence.xml com as informações corretas do mecanismo de persistência. Ao criar o banco de dados, tomar o cuidado de criar um schema com o nome data. Feitas as devidas configurações, empacotar o projeto com o comando `mvn package` na raiz do projeto.

No diretório scripts estão disponíveis quatro scripts que instanciam cada um dos nós da simulação, são eles: manager.sh, provider.sh, detector.sh e holder.sh. Execute-os em terminais diferentes nesta ordem a partir do diretório raiz do projeto, e.g., no diretório tcc execute ./scripts/manager.sh e assim por diante. Os logs do holder.sh e do manager.sh informarão se a simulação iniciou ou não com sucesso. A configuração executada sera a basic.conf que está no diretório simulations.

### Executando no cluster do PPGMMC ###

Para executar as simulações no cluster os mesmos arquivos de configuração do item anterior devem ser alterados, tomando o cuidado de colocar o real endereço IP do compute-0-34, onde o manager executará. Feitas as alterações é necessário executar o comando copyToCluster.sh <user> que empacotará o projeto e copiará as dependências necessárias para o diretório do usuário passado por parâmetro. Como vários arquivos são copiados um-a-um, é sugerido criar uma chave ssh para o seu usuário, evitando a digitação da senha a cada login.

Tendo terminado de copiar, basta acessar o cluster do PPGMMC e executar o script deploy.sh passando como parâmetro o arquivo de configuração da simulação e o número de repetições. 

### Analise de dados ###

Os holders, ao terminarem de executar, armazenarão os dados da simulação bem como os backups em uma pasta cujo nome será o id do processo no SLURM. Esses dados devem ser comprimidos e copiados de volta para o diretório do projeto para serem analisados.

Os scripts para analise de dados estão no diretório analysis, na raiz do projeto. Eles foram escritos em Python 2.7 utilizando a biblioteca numpy e scipy. Os principais arquivos são exp1.py, exp2.py, exp3.py e tracing.py. Em cada um deles deve ser alterada a variável `wd`, que aponta para o diretório onde estão os resultados das simulações. 


## Como contribuir ##

O código fonte do projeto está bem organizado em alguns poucos pacotes. São eles: 

* `analysis`: Neste pacote estão as classes responsáveis por executar as consultas de banco de dados, extrair, organizar e escrever os dados em um arquivo CSV. Existem dois tipos de dados, os que dizem respeito a amostra (os dados do conjunto de criaturas, e.g. nutrientes comidos, distância percorrida) e os que dizem respeito a dinâmica de cada criatura;
* `cluster`: Neste pacote estão as classes que definem os atores e mensagens de controle entre as entidades que formam o cluster. Essas entidades são o SimulationManager, o IdProvider, o CollisionDetector e o Holder. Cada um desses membros tem um papel claro na simulação e estes estão explicados na monografia; 
* `common`: Contem classes que são úteis para o desenvolvimento do projeto mas não fazem parte do seu objetivo central, como extensões do modelo de atores, estruturas de dados que complementam a biblioteca padrão Java, etc;
* `creature`: Os atores que formam a criatura artificial, bem como seus subsistemas, estão nesse pacote;
* `gui`: Pacote destinado aos componentes da interface gráfica;
* `physics`: Pacote destinado à representação física das criaturas e nutrientes no `CollisionDetector`, chamados de `Geometry`, e aos `PositioningAttributes`, classes usadas nos `holders` para transmitir informações de localização; 
* `stimuli`: Os estímulos trocados internamente entre os componentes da criatura e os estímulos trocados com o mundo artificial estão neste pacote;
* `world`: Entitades do mundo artificial, como nutrientes e predadores devem ficar neste pacote.

As classes não seguem todas o padrão JavaBeans, isso principalmente graças ao modelo de atores. Mensagens trocadas entre os atores devem ser imutáveis, por uma recomendação do _toolkit_ Akka. Portanto, seus atributos são todos do tipo `final`. Por serem constantes, eles devem ser setados pelo método construtor, e não podem ser alterados por métodos `set`. Neste sentido, os métodos get são na sua maioria desnecessários, pois não há o que encapsular. Mas isso deve ser bem analisado no projeto de novas classes, pois algum método pode ser eventualmente necessário.

Os componentes da criatura, bem como os do mundo e também as classes que formam o cluster da simulação são essencialmente atores, e por esse motivo, se comunicam exclusivamente por troca de mensagens. Algumas trocas podem ser síncronas, e há ferramentas para isso no pacote `common`, mas a maioria delas é assíncrona. Para fins de padronização, toda classe ou sub-sistema que tem uma interface bem definida (e.g. sistema de memória, ou sistema de condicionamento) e são acessados por mais de um componente foram projetados como atores tipados. Os demais componentes que funcionam por troca de estímulos, foram projetados como atores não-tipados. Não é recomendado fugir a esse padrão da arquitetura, forçando o compartilhamento de memória entre componentes, seja com o uso de _threads_ ou de outro artíficio, principalmente por ser uma restrição do modelo de atores que impacta diretamente no desempenho do sistema.

