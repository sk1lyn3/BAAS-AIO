package moe.baas.aio;

interface IOcrService {
    void start(String projectDir);
    void stop();
    String identity();
}
