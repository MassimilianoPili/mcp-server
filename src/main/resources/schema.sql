-- Schema di esempio per la POC MCP Server

CREATE TABLE IF NOT EXISTS prodotti (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nome VARCHAR(200) NOT NULL,
    categoria VARCHAR(100),
    prezzo DECIMAL(10, 2),
    quantita INT DEFAULT 0,
    data_inserimento TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS clienti (
    id INT AUTO_INCREMENT PRIMARY KEY,
    nome VARCHAR(100) NOT NULL,
    cognome VARCHAR(100) NOT NULL,
    email VARCHAR(200),
    citta VARCHAR(100),
    data_registrazione TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ordini (
    id INT AUTO_INCREMENT PRIMARY KEY,
    cliente_id INT,
    prodotto_id INT,
    quantita INT NOT NULL,
    totale DECIMAL(10, 2),
    stato VARCHAR(50) DEFAULT 'NUOVO',
    data_ordine TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (cliente_id) REFERENCES clienti(id),
    FOREIGN KEY (prodotto_id) REFERENCES prodotti(id)
);
