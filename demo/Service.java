import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Independently stoppable, loopback-only real HTTP service. */
class Service {
    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(args[0]);
        String service=port<9300 ? "catalog" : "orders";
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        var counter=new AtomicInteger();
        server.createContext("/",exchange->{
            try {
                String path=exchange.getRequestURI().getPath();
                boolean health=path.equals("/health");
                int sequence=health ? 0 : counter.incrementAndGet();
                if(path.equals("/slow")) Thread.sleep(2500);
                else if(!health) Thread.sleep(port%2==1 ? 200 : 50);
                int status=path.equals("/fail") ? 503 : 200;
                byte[] requestBody=exchange.getRequestBody().readNBytes(1048577);
                if(requestBody.length>1048576) status=413;
                if(service.equals("catalog") && exchange.getRequestMethod().equals("GET") && path.equals("/items")) {
                    exchange.getResponseHeaders().set("Cache-Control","public, max-age=5");
                }
                String body="{\"service\":\""+service+"\",\"port\":"+port+",\"sequence\":"+sequence
                        +",\"method\":\""+exchange.getRequestMethod()+"\",\"receivedBytes\":"+requestBody.length
                        +",\"at\":\""+Instant.now()+"\",\"message\":\""+(status==503 ? "Intentional upstream failure" : "Real service response")+"\"}";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                boolean head=exchange.getRequestMethod().equals("HEAD");
                exchange.sendResponseHeaders(status,head ? -1 : bytes.length);
                if(!head) exchange.getResponseBody().write(bytes);
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        System.out.println(service+" replica listening on http://127.0.0.1:"+port);
    }
}
