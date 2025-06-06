from pynput import mouse
from datetime import datetime

log_file = "logger.txt"

def log_data(timestamp ,event, value):
    with open(log_file, 'a') as f:
        f.write(f"{timestamp}|{event}|{value}\n")

#detect mouse movement
def on_move(x, y):
    print('Pointer moved to {0}'.format((x, y)))
    log_data(datetime.now(),'MouseMovement', str(x) + ',' + str(y))
    #print(datetime.now(),'MouseMovement', str(x) + ',' + str(y))


#detect mouse scroll
def on_scroll(x, y, dx, dy):
    print('Mouse scrolled {0} at {1}'.format('down' if dy < 0 else 'up', (x, y)))
    log_data(datetime.now(),'MouseScroll', str(x) + ',' + str(y) + ';' + str(dx) + ',' + str(dy))
    #print(datetime.now(),'MouseScroll', str(x) + ',' + str(y) + ';' + str(dx) + ',' + str(dy))


#detect mouse click
def on_click(x, y, button, pressed):
    
    print('{0} at {1}'.format('Pressed' if pressed else 'Released', (x, y)))
    log_data(datetime.now(),'MouseClicked', str(button))
    #print(datetime.now(),'MouseClicked', str(button))

    if not pressed: #stop listener
        print('Gracefully Stopping!')
        return False
    
#collecting events
with mouse.Listener(on_move=on_move, on_click=on_click, on_scroll=on_scroll) as listener:
    listener.join()
    
#in a non-blocking fashion
listener = mouse.Listener(on_move=on_move, on_click=on_click, on_scroll=on_scroll)
listener.start()
