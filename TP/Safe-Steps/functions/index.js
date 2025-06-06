const { onValueCreated } = require('firebase-functions/v2/database');
const admin = require('firebase-admin');
admin.initializeApp();

exports.sendNotification = onValueCreated('/notifications/{notificationId}', async (event) => {
    const snapshot = event.data;
    const notification = snapshot.val();
    
    console.log('Nova notificação recebida:', JSON.stringify(notification));
    
    if (!notification || !notification.to) {
        console.log('Notificação inválida, faltando token de destino');
        return null;
    }

    try {
        console.log('Verificando token antes de enviar:', notification.to);
        
        await admin.messaging().send({
            token: notification.to,
            data: { test: 'true' }
        }, true);
        
        const message = {
            token: notification.to,
            notification: {
                title: notification.notification.title,
                body: notification.notification.body
            },
            data: notification.data || {},
            android: {
                priority: 'high',
                notification: {
                    channelId: notification.data?.type === 'geofence_alert' || notification.data?.type === 'danger_zone_alert' || notification.data?.type === 'geofence_safe_return' 
                        ? 'geofence_channel'
                        : 'alerts_channel',
                    sound: 'default',
                    priority: 'high',
                    icon: 'ic_notification',
                    clickAction: 'OPEN_ACTIVITY_1'
                }
            }
        };

        const response = await admin.messaging().send(message);
        console.log('Notificação enviada com sucesso:', response);
        
        // Guardar log da notificação
        await admin.database().ref('notification_logs').push({
            recipient: notification.data?.caregiverId || notification.data?.caredId || 'unknown',
            type: notification.data?.type || 'unknown',
            sent_at: admin.database.ServerValue.TIMESTAMP,
            success: true
        });
        
        // Remover a notificação após o envio
        return snapshot.ref.remove();
    } catch (error) {
        console.error('Erro ao enviar notificação:', error);
        
        // Guardar log do erro
        await admin.database().ref('notification_logs').push({
            recipient: notification.data?.caregiverId || notification.data?.caredId || 'unknown',
            type: notification.data?.type || 'unknown',
            sent_at: admin.database.ServerValue.TIMESTAMP,
            success: false,
            error: error.message
        });
        
        return null;
    }
});


// Função para limpar tokens inválidos
exports.cleanupInvalidTokens = onValueCreated('/notification_logs/{logId}', async (event) => {
    const log = event.data.val();
    
    if (log && !log.success && log.error && log.error.includes('registration-token-not-registered')) {
        const userId = log.recipient;
        
        // Remover token inválido
        if (userId && userId !== 'unknown') {
            await admin.database().ref(`user_tokens/${userId}`).remove();
            console.log(`Token inválido removido para o usuário ${userId}`);
        }
    }
    
    return null;
});