class SimulationViewer {
    constructor() {
        this.canvas = document.getElementById('simulationCanvas');
        this.ctx = this.canvas.getContext('2d');
        this.isPaused = false;
        this.lastFrameTime = 0;
        this.entities = new Map();
        
        // Creature and object counts
        this.creatureCount = 0;
        this.objectCount = 0;
        
        // Colors and styles
        this.colors = {
            creature: '#4CAF50',
            object: '#2196F3',
            vision: 'rgba(255, 255, 255, 0.1)',
            olfactory: 'rgba(255, 192, 203, 0.1)',
            text: '#ffffff'
        };
        
        this.setupWebSocket();
        this.startRenderLoop();
        this.updateStats();
    }

    setupWebSocket() {
        this.ws = new WebSocket('ws://localhost:8090/geometry');
        
        this.ws.onmessage = (event) => {
            if (this.isPaused) return;
            
            const data = JSON.parse(event.data);
            this.updateEntity(data);
        };

        this.ws.onclose = () => {
            console.log('WebSocket connection closed');
            // Attempt to reconnect after 5 seconds
            setTimeout(() => this.setupWebSocket(), 5000);
        };
    }

    updateEntity(data) {
        if (data.type === 'remove') {
            this.entities.delete(data.id);
            return;
        }

        // Simulation uses math coords (origin bottom-left, Y up).
        // Flip Y so the viewer displays movement correctly relative to the canvas (origin top-left, Y down).
        this.entities.set(data.id, {
            type: data.type,
            x: data.x,
            y: this.canvas.height - data.y,
            objectType: data.objectType,
            angle: data.angle,
            lastUpdate: Date.now()
        });

        // Update counts
        this.creatureCount = Array.from(this.entities.values())
            .filter(e => e.type === 'creature').length;
        this.objectCount = Array.from(this.entities.values())
            .filter(e => e.type === 'object').length;
    }

    drawCreature(entity) {
        const ctx = this.ctx;

        // Draw vision field — angle from server is in math coords (Y up); negate for canvas (Y down)
        const facing = entity.angle != null ? -entity.angle : 0;
        const halfCone = Math.PI / 6; // 60° cone
        ctx.beginPath();
        ctx.fillStyle = this.colors.vision;
        ctx.moveTo(entity.x, entity.y);
        ctx.arc(entity.x, entity.y, 50, facing - halfCone, facing + halfCone);
        ctx.closePath();
        ctx.fill();
        
        // Draw olfactory field
        ctx.beginPath();
        ctx.fillStyle = this.colors.olfactory;
        ctx.arc(entity.x, entity.y, 30, 0, Math.PI * 2);
        ctx.fill();
        
        // Draw creature body
        ctx.beginPath();
        ctx.fillStyle = this.colors.creature;
        ctx.arc(entity.x, entity.y, 5, 0, Math.PI * 2);
        ctx.fill();
    }

    drawObject(entity) {
        const ctx = this.ctx;
        ctx.beginPath();
        ctx.fillStyle = this.colors.object;
        ctx.arc(entity.x, entity.y, 3, 0, Math.PI * 2);
        ctx.fill();
        
        // Draw object type
        if (entity.objectType) {
            ctx.fillStyle = this.colors.text;
            ctx.font = '10px Arial';
            ctx.fillText(entity.objectType, entity.x + 5, entity.y + 5);
        }
    }

    render(timestamp) {
        if (!this.isPaused) {
            this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
            
            // Calculate FPS
            const fps = 1000 / (timestamp - this.lastFrameTime);
            this.lastFrameTime = timestamp;
            document.getElementById('fps').textContent = Math.round(fps);

            // Remove stale creatures (objects are static and stay until eaten)
            const now = Date.now();
            for (const [id, entity] of this.entities) {
                if (entity.type === 'creature' && now - entity.lastUpdate > 5000) {
                    this.entities.delete(id);
                }
            }

            // Draw all entities
            for (const entity of this.entities.values()) {
                if (entity.type === 'creature') {
                    this.drawCreature(entity);
                } else {
                    this.drawObject(entity);
                }
            }
        }

        requestAnimationFrame(this.render.bind(this));
    }

    startRenderLoop() {
        requestAnimationFrame(this.render.bind(this));
    }

    updateStats() {
        document.getElementById('creatureCount').textContent = this.creatureCount;
        document.getElementById('objectCount').textContent = this.objectCount;
        setTimeout(() => this.updateStats(), 1000);
    }

    togglePause() {
        this.isPaused = !this.isPaused;
    }

    clearCanvas() {
        this.entities.clear();
        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
        this.creatureCount = 0;
        this.objectCount = 0;
    }
}

// Global functions for button controls
function togglePause() {
    window.viewer.togglePause();
}

function clearCanvas() {
    window.viewer.clearCanvas();
}

// Start the simulation viewer when the page loads
window.onload = () => {
    window.viewer = new SimulationViewer();
};