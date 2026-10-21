package com.pocketserver.ui.onboarding

/**
 * Definition of a 1-tap quick start sample project for zero-data onboarding.
 */
data class StarterTemplate(
    val id: String,
    val title: String,
    val description: String,
    val runtimeName: String,
    val defaultPort: Int,
    val category: String, // "Backend", "Frontend", "Fullstack", "Microservice"
    val startCommand: List<String>,
    val templateFiles: Map<String, String>
) {
    companion object {
        val FASTAPI_HELLO_WORLD = StarterTemplate(
            id = "template-fastapi",
            title = "FastAPI Hello World",
            description = "Lightweight Python REST API. Ready in 5 seconds.",
            runtimeName = "Python 3.12",
            defaultPort = 8000,
            category = "Backend",
            startCommand = listOf("python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"),
            templateFiles = mapOf(
                "main.py" to """
                    from fastapi import FastAPI
                    import os

                    app = FastAPI(title="PocketServer Sample API")

                    @app.get("/")
                    def read_root():
                        return {
                            "status": "online",
                            "message": "Hello from PocketServer on Android!",
                            "device_runtime": "Non-root hermetic container"
                        }

                    @app.get("/health")
                    def health():
                        return {"health": "healthy", "port": os.getenv("PORT", "8000")}
                """.trimIndent(),
                "requirements.txt" to """
                    fastapi>=0.115.0
                    uvicorn[standard]>=0.32.0
                """.trimIndent(),
                ".env" to """
                    PORT=8000
                    ENVIRONMENT=development
                    DEBUG=True
                    DATABASE_URL=sqlite:///./app.sqlite
                """.trimIndent(),
                "README.md" to """
                    # FastAPI Hello World on PocketServer
                    Hosted directly on your Android phone with zero root.
                """.trimIndent()
            )
        )

        val REACT_VITE_FRONTEND = StarterTemplate(
            id = "template-vite",
            title = "React / Vite Landing Page",
            description = "Fast static web frontend. Consumes < 2MB RAM.",
            runtimeName = "Bun 1.2 (Node 22 API)",
            defaultPort = 3000,
            category = "Frontend",
            startCommand = listOf("bun", "run", "dev", "--port", "3000", "--host", "0.0.0.0"),
            templateFiles = mapOf(
                "package.json" to """
                    {
                      "name": "vite-pocket-frontend",
                      "private": true,
                      "version": "1.0.0",
                      "type": "module",
                      "scripts": {
                        "dev": "vite",
                        "build": "vite build",
                        "preview": "vite preview"
                      },
                      "dependencies": {
                        "react": "^18.3.1",
                        "react-dom": "^18.3.1"
                      },
                      "devDependencies": {
                        "@vitejs/plugin-react": "^4.3.3",
                        "vite": "^5.4.10"
                      }
                    }
                """.trimIndent(),
                "index.html" to """
                    <!DOCTYPE html>
                    <html lang="en">
                      <head>
                        <meta charset="UTF-8" />
                        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                        <title>PocketServer Frontend</title>
                      </head>
                      <body style="margin:0; background:#09090b; color:#f4f4f5; font-family:sans-serif;">
                        <div id="root">
                          <h1 style="color:#6366f1;">PocketServer Frontend</h1>
                          <p>Served locally from your mobile device via Bun 1.2.</p>
                        </div>
                      </body>
                    </html>
                """.trimIndent(),
                ".env" to """
                    PORT=3000
                    VITE_API_URL=http://localhost:8000
                """.trimIndent()
            )
        )

        val GO_MICROSERVICE = StarterTemplate(
            id = "template-go",
            title = "Go Microservice",
            description = "High-throughput REST API with minimal footprint.",
            runtimeName = "Go 1.23 ARM64",
            defaultPort = 8080,
            category = "Backend",
            startCommand = listOf("go", "run", "main.go"),
            templateFiles = mapOf(
                "main.go" to """
                    package main

                    import (
                        "fmt"
                        "net/http"
                        "os"
                    )

                    func main() {
                        port := os.Getenv("PORT")
                        if port == "" {
                            port = "8080"
                        }
                        http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
                            fmt.Fprintf(w, `{"service":"go-microservice","status":"running"}`)
                        })
                        fmt.Println("PocketServer Go Service running on port " + port)
                        http.ListenAndServe(":"+port, nil)
                    }
                """.trimIndent(),
                "go.mod" to """
                    module pocketserver/go-sample

                    go 1.23
                """.trimIndent(),
                ".env" to "PORT=8080\nLOG_LEVEL=info\n"
            )
        )

        val BUN_ELYSIA = StarterTemplate(
            id = "template-elysia",
            title = "Bun Elysia Backend",
            description = "Ultra-fast TypeScript API powered by Bun.",
            runtimeName = "Bun 1.2",
            defaultPort = 3001,
            category = "Backend",
            startCommand = listOf("bun", "run", "src/index.ts"),
            templateFiles = mapOf(
                "src/index.ts" to """
                    import { Elysia } from 'elysia'

                    const app = new Elysia()
                        .get('/', () => ({ message: 'Hello from Elysia on PocketServer' }))
                        .listen(process.env.PORT || 3001)

                    console.log(`🦊 Elysia is running at ${'$'}{app.server?.hostname}:${'$'}{app.server?.port}`)
                """.trimIndent(),
                "package.json" to """
                    {
                      "name": "elysia-api",
                      "module": "src/index.ts",
                      "type": "module",
                      "devDependencies": {
                        "bun-types": "latest"
                      },
                      "peerDependencies": {
                        "typescript": "^5.0.0"
                      },
                      "dependencies": {
                        "elysia": "latest"
                      }
                    }
                """.trimIndent(),
                ".env" to "PORT=3001\nNODE_ENV=development\n"
            )
        )

        val RUST_AXUM = StarterTemplate(
            id = "template-rust",
            title = "Rust Axum Microservice",
            description = "Zero-overhead async web service compiled natively.",
            runtimeName = "Rust 1.82 + Cargo",
            defaultPort = 8081,
            category = "Backend",
            startCommand = listOf("cargo", "run", "--release"),
            templateFiles = mapOf(
                "Cargo.toml" to """
                    [package]
                    name = "pocket-axum"
                    version = "0.1.0"
                    edition = "2021"

                    [dependencies]
                    axum = "0.7"
                    tokio = { version = "1.0", features = ["full"] }
                """.trimIndent(),
                "src/main.rs" to """
                    use axum::{routing::get, Router};
                    use std::net::SocketAddr;

                    #[tokio::main]
                    async fn main() {
                        let app = Router::new().route("/", get(|| async { "Hello from Rust Axum on Android!" }));
                        let addr = SocketAddr::from(([0, 0, 0, 0], 8081));
                        println!("Listening on {}", addr);
                        let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
                        axum::serve(listener, app).await.unwrap();
                    }
                """.trimIndent(),
                ".env" to "PORT=8081\nRUST_LOG=info\n"
            )
        )

        val ALL_TEMPLATES = listOf(
            FASTAPI_HELLO_WORLD,
            REACT_VITE_FRONTEND,
            GO_MICROSERVICE,
            BUN_ELYSIA,
            RUST_AXUM
        )
    }
}
