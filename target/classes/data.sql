-- Dati di esempio per la POC MCP Server

MERGE INTO prodotti (id, nome, categoria, prezzo, quantita) KEY(id) VALUES
(1, 'Laptop Pro 15', 'Elettronica', 1299.99, 25),
(2, 'Mouse Wireless', 'Elettronica', 29.99, 150),
(3, 'Tastiera Meccanica', 'Elettronica', 89.99, 80),
(4, 'Monitor 27 pollici', 'Elettronica', 449.99, 30),
(5, 'Cavo USB-C', 'Accessori', 12.99, 500),
(6, 'Zaino porta PC', 'Accessori', 59.99, 60),
(7, 'Webcam HD', 'Elettronica', 79.99, 45),
(8, 'Cuffie Bluetooth', 'Audio', 149.99, 70);

MERGE INTO clienti (id, nome, cognome, email, citta) KEY(id) VALUES
(1, 'Mario', 'Rossi', 'mario.rossi@email.it', 'Roma'),
(2, 'Giulia', 'Bianchi', 'giulia.bianchi@email.it', 'Milano'),
(3, 'Luca', 'Verdi', 'luca.verdi@email.it', 'Napoli'),
(4, 'Anna', 'Neri', 'anna.neri@email.it', 'Torino'),
(5, 'Paolo', 'Gialli', 'paolo.gialli@email.it', 'Firenze');

MERGE INTO ordini (id, cliente_id, prodotto_id, quantita, totale, stato) KEY(id) VALUES
(1, 1, 1, 1, 1299.99, 'COMPLETATO'),
(2, 1, 2, 2, 59.98, 'COMPLETATO'),
(3, 2, 3, 1, 89.99, 'SPEDITO'),
(4, 3, 4, 1, 449.99, 'NUOVO'),
(5, 4, 5, 3, 38.97, 'COMPLETATO'),
(6, 2, 8, 1, 149.99, 'SPEDITO'),
(7, 5, 6, 1, 59.99, 'NUOVO'),
(8, 3, 7, 2, 159.98, 'NUOVO');
