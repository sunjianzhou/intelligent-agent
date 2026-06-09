// websocket-mock.js - WebSocket模拟服务
export class WebSocketMock {
  constructor(url) {
    this.url = url
    this.onopen = null
    this.onmessage = null
    this.onclose = null
    this.onerror = null
    this.readyState = 0  // CONNECTING
    
    // 模拟连接过程
    this.connect()
  }
  
  connect() {
    console.log('🔌 模拟WebSocket连接:', this.url)
    
    // 模拟连接延迟
    setTimeout(() => {
      this.readyState = 1  // OPEN
      
      if (this.onopen) {
        this.onopen({ type: 'open' })
      }
      
      // 发送连接确认消息
      setTimeout(() => {
        if (this.onmessage) {
          this.onmessage({
            data: JSON.stringify({
              type: 'connection_established',
              message: 'WebSocket连接已建立（模拟）',
              timestamp: new Date().toISOString()
            })
          })
        }
        
        // 发送模拟的系统信息
        setTimeout(() => {
          if (this.onmessage) {
            this.onmessage({
              data: JSON.stringify({
                type: 'system_info',
                info: {
                  agent_status: '已连接（模拟）',
                  agent_model: 'dolphin',
                  platform: 'Windows 10',
                  cpu_percent: 25.5,
                  memory_percent: 45.2,
                  time: new Date().toLocaleString(),
                  tools_count: 6
                }
              })
            })
          }
        }, 500)
      }, 200)
    }, 1000)
  }
  
  send(data) {
    console.log('📤 发送消息（模拟）:', data)
    
    // 解析消息
    let parsed
    try {
      parsed = JSON.parse(data)
    } catch (e) {
      console.error('解析消息失败:', e)
      return
    }
    
    // 模拟不同类型的响应
    if (parsed.type === 'chat_message') {
      // 立即回复思考状态
      if (this.onmessage) {
        this.onmessage({
          data: JSON.stringify({
            type: 'thinking',
            message: '正在思考...',
            request_id: 'mock-' + Date.now()
          })
        })
      }
      
      // 模拟AI响应
      setTimeout(() => {
        if (this.onmessage) {
          const responses = [
            `我收到了你的消息："${parsed.message}"。这是一条模拟响应。`,
            `你好！我收到了："${parsed.message}"。后端WebSocket服务正常后，我将能够真正回答您的问题。`,
            `当前是模拟模式。您说："${parsed.message}"，我会在后台服务就绪后处理。`,
            `模拟响应：已收到您的查询"${parsed.message.substring(0, 20)}..."，后端服务正在连接中。`,
            `这是一条测试响应。您的消息是："${parsed.message}"`
          ]
          
          const randomResponse = responses[Math.floor(Math.random() * responses.length)]
          
          this.onmessage({
            data: JSON.stringify({
              type: 'chat_response',
              message: randomResponse,
              response_time: Math.random() * 2 + 0.5, // 0.5-2.5秒
              request_id: 'mock-' + Date.now()
            })
          })
        }
      }, Math.random() * 1500 + 500) // 0.5-2秒延迟
    }
  }
  
  close() {
    console.log('🔌 关闭模拟WebSocket连接')
    this.readyState = 2  // CLOSING
    
    setTimeout(() => {
      this.readyState = 3  // CLOSED
      if (this.onclose) {
        this.onclose({ code: 1000, reason: '正常关闭' })
      }
    }, 100)
  }
}
