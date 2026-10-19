# Simulador SIC/XE

Este projeto é um simulador didático do computador hipotético SIC/XE, desenvolvido em Java para a disciplina de Programação de Sistemas do curso de Ciência da Computação da Universidade Federal de Pelotas (UFPel). O sistema foi projetado com base na arquitetura descrita no livro "System Software: An Introduction to Systems Programming" de Leland L. Beck.

## Pré-requisitos
Para compilar e executar este simulador, a sua máquina deve possuir:
* **Java Development Kit (JDK) 21**
* **Apache Maven**

## Instalação Rápida (Scripts Inteligentes)
Para facilitar a configuração do ambiente, utilize os scripts fornecidos na pasta `setup-scripts`. Eles verificam automaticamente se as ferramentas já estão instaladas; caso contrário, realizam o download e a configuração no sistema operacional.

**No Windows:**
Abra um terminal (PowerShell ou CMD) **como Administrador**, navegue até a raiz do projeto e execute o script:
```cmd
.\setup-scripts\setup.bat
```

**No Linux (Debian/Ubuntu):**
```bash
chmod +x setup-scripts/setup.sh
./setup-scripts/setup.sh
```

## 🛠️ Como Compilar e Executar
O projeto utiliza o Apache Maven para a gestão de dependências e automação do ciclo de vida (*build*, *test*, *run*). Utilize os seguintes comandos no terminal, executados a partir da **raiz do repositório**:

* **Para compilar o projeto inteiro:** 
  ```bash
  mvn clean compile
  ```
* **Para executar a Interface Gráfica:** 
  ```bash
  mvn clean compile exec:java
  ```
* **Para executar a bateria de testes automatizados (JUnit 5):** 
  ```bash
  mvn clean test
  ```

## Montador (Segunda Etapa)

O projeto também inclui um montador SIC/XE de duas passagens, integrado à máquina virtual. Na aba **Montador**, abra ou edite um `.asm`, clique em **Montar** e depois em **Carregar na VM**. Os exemplos estão em `test-programs/loop.asm`, `addressing.asm` e `literals.asm`.

O resumo do funcionamento, das estruturas e dos comandos do montador está ao final da [documentação do sistema](docs/DOCUMENTATION.md#8-montador-e-carregador-segunda-etapa).

No Windows, `executar.cmd` abre a aplicação. Se o Maven não estiver no PATH, use `.\maven.cmd` no lugar de `mvn` nos comandos acima. Para gerar um JAR executável, use `mvn package` e execute `java -jar target/sicxe-simulator-1.0-SNAPSHOT.jar`.
