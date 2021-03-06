package me.hebaceous.grpc.demo.service;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.Empty;
import com.google.protobuf.Int32Value;
import io.grpc.*;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import me.hebaceous.grpc.UserProto;
import me.hebaceous.grpc.UserServiceGrpc.UserServiceBlockingStub;
import me.hebaceous.grpc.UserServiceGrpc.UserServiceFutureStub;
import me.hebaceous.grpc.UserServiceGrpc.UserServiceStub;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Nullable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import static me.hebaceous.grpc.UserServiceGrpc.newBlockingStub;
import static me.hebaceous.grpc.UserServiceGrpc.newFutureStub;
import static me.hebaceous.grpc.UserServiceGrpc.newStub;

@RunWith(SpringRunner.class)
@SpringBootTest
public class GrpcApplicationTests {

    private final static Logger LOGGER = LoggerFactory.getLogger(GrpcApplicationTests.class);

    @Value("${grpc.port}")
    private int grpcPort;

    private ManagedChannel managedChannel;
    private UserServiceBlockingStub userServiceBlockingStub;
    private UserServiceStub userServiceStub;
    private UserServiceFutureStub userServiceFutureStub;

    private class GrpcClientInterceptor implements ClientInterceptor {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    LOGGER.info("ClientInterceptor:headers:send:{}", headers);
                    super.start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
                        @Override
                        public void onHeaders(Metadata headers) {
                            LOGGER.info("ClientInterceptor:headers:receive:{}", headers);
                            super.onHeaders(headers);
                        }
                    }, headers);
                }
            };
        }
    }

    @Before
    public void before() {
        managedChannel = ManagedChannelBuilder.forAddress("localhost", grpcPort)
                .usePlaintext(true)
                .intercept(new GrpcClientInterceptor())
                .build();
        userServiceBlockingStub = newBlockingStub(managedChannel);
//        userServiceBlockingStub.withCompression("gzip");
        userServiceStub = newStub(managedChannel);
//        userServiceStub.withCompression("gzip");
        userServiceFutureStub = newFutureStub(managedChannel);
//        userServiceFutureStub.withCompression("gzip");
    }

    @After
    public void after() {
        managedChannel.shutdown();
    }

    @Test
    public void testAllBlock() {
        userServiceBlockingStub.all(Empty.getDefaultInstance())
                .forEachRemaining(user -> System.out.println(user.getId() + ":" + user.getName()));
    }

    @Test
    public void testAllAsync() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        userServiceStub.all(Empty.getDefaultInstance(), new ClientResponseObserver<Empty, UserProto.User>() {
            @Override
            public void onNext(UserProto.User value) {
                LOGGER.info("onNext:{}", value);
            }

            @Override
            public void onError(Throwable t) {
                LOGGER.info("onError", t);
                countDownLatch.countDown();
            }

            @Override
            public void onCompleted() {
                LOGGER.info("onCompleted");
                countDownLatch.countDown();
            }

            @Override
            public void beforeStart(ClientCallStreamObserver<Empty> requestStream) {
                LOGGER.info("beforeStart:{}", requestStream);
            }
        });
        countDownLatch.await();
    }

    @Test
    public void testFetchById() {
        Int32Value id = Int32Value.newBuilder().setValue(5).build();
        UserProto.User user = userServiceBlockingStub.fetchById(id);
        System.out.println(user.getId() + ":" + user.getName());
    }

    @Test
    public void testFetchByIdFuture() throws InterruptedException, ExecutionException {
        Int32Value id = Int32Value.newBuilder().setValue(5).build();
        ListenableFuture<UserProto.User> listenableFuture = userServiceFutureStub.fetchById(id);

        // 直接get
        UserProto.User user = listenableFuture.get();
        System.out.println(user);

        CountDownLatch countDownLatch = new CountDownLatch(1);
        // guava
        Futures.addCallback(listenableFuture, new FutureCallback<UserProto.User>() {
            @Override
            public void onSuccess(@Nullable UserProto.User result) {
                System.out.println(result);
                countDownLatch.countDown();
            }

            @Override
            public void onFailure(Throwable t) {
                LOGGER.error("FutureCallback onFailure", t);
                countDownLatch.countDown();
            }
        });
        countDownLatch.await();
    }
}
