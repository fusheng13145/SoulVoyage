package com.soulvoyage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulvoyage.account.AccountService;
import com.soulvoyage.domain.user.UserEntity;
import com.soulvoyage.domain.user.UserRepository;
import com.soulvoyage.wechat.WxBindTicketStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M14 小程序端登录（§11.3「V3.0 微信小程序多端」首块地基）回归。
 *
 * 这一层要钉住的是"多端不换来第二套账号规则"：
 * ① 未绑定的微信只拿到一张一次性票，openid 不出现在任何响应里；
 * ② 绑定/注册复用 Web 同一条 authenticate/registerUser（失败锁定、重名与口令强度校验、留痕一个都不少）；
 * ③ 填错口令不烧票——与"越权领取不导出个人数据"是同一条立场；
 * ④ 一号一微信是双向的（这个微信有了主人要被拒，这个账号已有微信也要被拒）；
 * ⑤ 解绑属主独享且即时生效，注销执行时绑定随其它个人信息一起释放；
 * ⑥ 冻结账号不因换了入口而放行；匿名可达的登录口先过 IP 滑动窗。
 *
 * 传输层刻意用 JDK 自带的 java.net.http 而不是 TestRestTemplate：后者的 HttpURLConnection
 * 在"请求体已流出 + 响应 401"时抛 HttpRetryException（cannot retry … in streaming mode），
 * 而本类近半用例断言的正是 401（口令错、无令牌）。401 是这里要验的正常业务答案，不能被客户端实现吃掉。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class M14WechatAuthTest {

    private static final String PWD = "Passw0rd!2026";
    private static final String WX = "/api/v1/auth/wechat";
    /** 信封里的成功码是 0（ApiResponse.ok），不是 HTTP 200 */
    private static final int OK = 0;
    private static final java.util.concurrent.atomic.AtomicLong IP_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    @LocalServerPort
    int port;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepo;
    @Autowired AccountService account;
    @Autowired WxBindTicketStore tickets;

    @Value("${soulvoyage.account.deletion-cooldown}")
    private Duration cooldown;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** 本次用例发出的票，收尾按精确 key 逐个删（本机 Redis 多项目共用，绝不做模式匹配清键） */
    private final List<String> issued = new ArrayList<>();

    record Actor(String jwt, long uid, String username, String ip) {}

    // ------------------------------------------------ ① 未绑定只回票，零身份标识泄露

    @Test
    void unboundWechatGetsOnlyATicketAndNoOpenidLeaks() throws Exception {
        String ip = ip();
        HttpResponse<String> r = call(HttpMethod.POST, WX + "/login", Map.of("code", code("a1")), null, ip);
        JsonNode d = data(r);
        assertFalse(d.path("bound").asBoolean(), "全新微信不该被当成已有账号");
        assertTrue(d.path("token").isNull(), "未绑定不发半吊子令牌");
        String ticket = d.path("bindTicket").asText();
        issued.add(ticket);
        assertEquals(32, ticket.length(), "票是一串随机十六进制，本身不携带任何业务含义");
        assertEquals(600, d.path("ticketExpiresInSeconds").asLong());

        assertFalse(r.body().contains("openid"), "响应不得出现 openid 字段名");
        assertFalse(r.body().contains("mock-"), "响应不得出现 openid 取值（Mock 前缀即泄露探针）");

        // 空白凭证在敲供应商之前就被挡掉
        assertEquals(2001, codeOf(call(HttpMethod.POST, WX + "/login", Map.of("code", "   "), null, ip)),
                "空白 jscode 应 2001");
        // 微信侧拒绝 → 专属业务码 + 中文话术：不退化成 500，也不把用户判成参数错
        HttpResponse<String> boom = call(HttpMethod.POST, WX + "/login", Map.of("code", "wx-boom-1"), null, ip);
        assertEquals(1005, codeOf(boom));
        assertEquals(502, boom.statusCode(), "上游故障是 502，响应: " + brief(boom.body()));
    }

    // ------------------------------------------------ ②③ 口令错不烧票，票用后即焚

    @Test
    void wrongPasswordKeepsTheTicketAndRightPasswordFinishesTheBinding() throws Exception {
        Actor a = registerViaWeb("m14a");
        String ip = ip();
        String ticket = ticketFor(code("b1"), ip);

        HttpResponse<String> wrong = wxBind(ticket, a.username(), "WrongPwd!2026", ip);
        assertEquals(1003, codeOf(wrong), "错口令应回与 Web 登录完全相同的 LOGIN_FAILED");
        assertEquals(401, wrong.statusCode(), "401 语义与 Web 登录一致，小程序不该拿到一个更松的状态码");
        assertTrue(userRepo.findById(a.uid()).orElseThrow().getWxOpenid() == null, "失败不该留下半条绑定");

        JsonNode ok = data(wxBind(ticket, a.username(), PWD, ip));
        assertEquals(a.uid(), ok.path("userId").asLong(), "绑定后直接回正式令牌，且属主不变");
        assertNotNull(userRepo.findById(a.uid()).orElseThrow().getWxOpenid());

        assertEquals(1006, codeOf(wxBind(ticket, a.username(), PWD, ip)),
                "一张票只完成一次绑定：用后即焚，第二次回 1006 而不是静默成功");
    }

    // ------------------------------------------------ ④ 绑定后同微信再登录回到同一账号

    @Test
    void boundWechatLoginsIntoTheSameAccountWithoutAPassword() throws Exception {
        Actor a = registerViaWeb("m14b");
        String ip = ip();
        String wxCode = code("b2");
        bindTo(wxCode, a, ip);

        JsonNode again = data(call(HttpMethod.POST, WX + "/login", Map.of("code", wxCode), null, ip));
        assertTrue(again.path("bound").asBoolean(), "同一微信两次登录必须落回同一账号");
        assertEquals(a.uid(), again.path("token").path("userId").asLong());
        assertFalse(again.path("token").path("accessToken").asText().isBlank());
        assertTrue(again.path("bindTicket").isNull(), "已绑定时不再发票");
        // 小程序拿到的令牌与 Web 同构：直接可用，无需第二次授权
        assertEquals(a.uid(), get(a, "/api/v1/auth/me").path("userId").asLong());
    }

    // ------------------------------------------------ ⑤ 冻结账号不因换入口而放行

    @Test
    void frozenAccountCannotEnterThroughWechat() throws Exception {
        Actor a = registerViaWeb("m14c");
        String ip = ip();
        String wxCode = code("c0");
        bindTo(wxCode, a, ip);

        UserEntity u = userRepo.findById(a.uid()).orElseThrow();
        u.setStatus((short) 2);
        userRepo.save(u);
        HttpResponse<String> r = call(HttpMethod.POST, WX + "/login", Map.of("code", wxCode), null, ip);
        assertEquals(1002, codeOf(r), "status=2 的账号走微信同样进不来");
        assertTrue(r.body().contains("不可登录"));
    }

    // ------------------------------------------------ ⑥ 一号一微信，双向都拒

    @Test
    void bindingIsOneToOneInBothDirections() throws Exception {
        Actor a = registerViaWeb("m14d");
        Actor b = registerViaWeb("m14e");
        String ip = ip();

        // 未绑定时每次登录各发一张票：先备好同一微信的两张票，再绑其中一张
        String shared = code("d1");
        String t1 = ticketFor(shared, ip);
        String t2 = ticketFor(shared, ip);
        data(wxBind(t1, a.username(), PWD, ip));

        assertEquals(1002, codeOf(wxBind(t2, b.username(), PWD, ip)), "这个微信已经有主人");
        assertEquals(1002, codeOf(wxBind(ticketFor(code("d2"), ip), a.username(), PWD, ip)),
                "这个账号已经绑定了另一个微信 → 同样拒");
        assertNull(userRepo.findById(b.uid()).orElseThrow().getWxOpenid(), "被拒的尝试不该顺手改数据");
    }

    // ------------------------------------------------ ⑦ 注册模式复用 Web 同一条建号校验

    @Test
    void registerModeReusesWebValidationAndReturnsTicketOnConflict() throws Exception {
        Actor a = registerViaWeb("m14f");
        String ip = ip();
        String ticket = ticketFor(code("f1"), ip);

        assertEquals(1004, codeOf(wxRegister(ticket, a.username(), PWD, null, ip)),
                "重名走 Web 注册同一条校验，不是新写一套");
        // 弱口令/非法用户名由 RegisterReq 的同一组约束挡下：到了另一个端不许松一档
        assertEquals(2001, codeOf(wxRegister(ticket, "m14f_new_user", "short", null, ip)),
                "短口令必须被挡住，否则小程序就成了全站最弱的入口");
        assertEquals(2001, codeOf(wxRegister(ticket, "微信昵称", PWD, null, ip)), "非法用户名字符同样挡住");

        String fresh = code("f2");
        JsonNode d = data(wxRegister(ticketFor(fresh, ip), "m14f" + System.nanoTime(), PWD, "小程序用户", ip));
        UserEntity u = userRepo.findById(d.path("userId").asLong()).orElseThrow();
        assertNotNull(u.getWxOpenid(), "注册即绑定，不留\"有账号但微信没连上\"的中间态");
        assertEquals("小程序用户", u.getNickname());
        assertEquals("USER", d.path("role").asText());
        assertFalse(d.path("deletionPending").asBoolean());
    }

    // ------------------------------------------------ ⑧ 解绑属主独享、即时生效

    @Test
    void unbindIsOwnerOnlyAndTakesEffectImmediately() throws Exception {
        Actor a = registerViaWeb("m14g");
        Actor b = registerViaWeb("m14h");
        String ip = ip();
        String wxCode = code("g1");
        bindTo(wxCode, a, ip);

        JsonNode me = get(a, "/api/v1/auth/me");
        assertTrue(me.path("wechatBound").asBoolean(), "设置页要能显示绑没绑");
        assertFalse(me.toString().contains("mock-"), "只回\"绑没绑\"，不回 openid");

        // 别人的令牌删不掉 A 的绑定：未绑定的账号解绑回明确话术，而不是假装成功
        assertEquals(2001, codeOf(call(HttpMethod.DELETE, WX + "/binding", null, b.jwt(), b.ip())));
        assertNotNull(userRepo.findById(a.uid()).orElseThrow().getWxOpenid(), "B 的操作不该动 A 的绑定");

        assertEquals(OK, codeOf(call(HttpMethod.DELETE, WX + "/binding", null, a.jwt(), ip)));
        assertNull(userRepo.findById(a.uid()).orElseThrow().getWxOpenid());
        assertFalse(get(a, "/api/v1/auth/me").path("wechatBound").asBoolean(), "下一次读资料即回未绑定");
        assertFalse(data(call(HttpMethod.POST, WX + "/login", Map.of("code", wxCode), null, ip))
                .path("bound").asBoolean(), "解绑即时生效：同一个微信回到未绑定分支");
    }

    // ------------------------------------------------ ⑨ 注销执行释放第三方绑定

    @Test
    void deletionExecutionReleasesTheWechatBinding() throws Exception {
        Actor a = registerViaWeb("m14i");
        String ip = ip();
        String wxCode = code("i1");
        bindTo(wxCode, a, ip);

        assertEquals(OK, codeOf(call(HttpMethod.POST, "/api/v1/users/me/delete",
                Map.of("password", PWD), a.jwt(), ip)), "注销申请仍是既有的口令二次确认，小程序不开后门");
        UserEntity u = userRepo.findById(a.uid()).orElseThrow();
        u.setDeletionRequestedAt(Instant.now().minus(cooldown).minus(Duration.ofDays(1)));
        userRepo.save(u);
        assertTrue(account.processExpiredDeletions() >= 1, "到期申请必须被执行");

        assertNull(userRepo.findById(a.uid()).orElseThrow().getWxOpenid(),
                "注销即遗忘：第三方身份绑定随其它个人信息一起置空");

        // 该微信此后可以绑到别的账号上，而不是被一个已注销的人永久占住
        Actor c = registerViaWeb("m14j");
        data(wxBind(ticketFor(wxCode, ip), c.username(), PWD, ip));
        assertNotNull(userRepo.findById(c.uid()).orElseThrow().getWxOpenid());
    }

    // ------------------------------------------------ ⑩ 匿名可达 vs 数据要令牌

    @Test
    void anonymousLoginEndpointIsReachableButDataIsNot() throws Exception {
        String ip = ip();
        assertNotNull(ticketFor(code("j1"), ip), "登录口本就匿名：permitAll 清单要带上它");
        assertEquals(2001, codeOf(call(HttpMethod.POST, WX + "/bind",
                Map.of("bindTicket", "deadbeef"), null, ip)), "缺 username/password 的绑定请求由校验层挡下");
        HttpResponse<String> ghost = call(HttpMethod.POST, WX + "/bind",
                Map.of("bindTicket", "deadbeef", "username", "whatever", "password", PWD), null, ip);
        assertEquals(1006, codeOf(ghost), "凭空捏造的票只能换来 1006，不会碰到任何账号");
        assertEquals(400, ghost.statusCode(), "过期的票是流程问题（重登一次即可），不是未登录也不是服务端故障");
        assertEquals(1001, codeOf(call(HttpMethod.GET, "/api/v1/diaries?page=0&size=10", null, null, ip)),
                "数据接口仍要令牌");
        HttpResponse<String> anonUnbind = call(HttpMethod.DELETE, WX + "/binding", null, null, ip);
        assertEquals(1001, codeOf(anonUnbind), "解绑必须有登录态");
        assertEquals(401, anonUnbind.statusCode());
    }

    // ------------------------------------------------ ⑪ 匿名口的自有配额自己守

    @Test
    void anonymousLoginEndpointIsWindowedPerIp() throws Exception {
        String ip = ip();
        for (int i = 0; i < 20; i++) {
            assertNotNull(ticketFor(code("k" + i), ip), "第 " + (i + 1) + " 次应放行（上限 20/分）");
        }
        HttpResponse<String> over = call(HttpMethod.POST, WX + "/login", Map.of("code", code("k99")), null, ip);
        assertEquals(4002, codeOf(over), "第 21 次由 IP 滑动窗挡下，不去消耗微信侧配额");
        assertEquals(429, over.statusCode(), "限流语义要落在状态码上，供小程序区分\"稍后再试\"");
        // 另一个出口不受牵连：闸门是每 IP 的，不是全局的
        assertEquals(OK, codeOf(call(HttpMethod.POST, WX + "/login", Map.of("code", code("k98")), null, ip())));
    }

    // ---------------------------------------------------------------- cleanup & helpers

    @AfterEach
    void dropTickets() {
        issued.forEach(tickets::drop);
        issued.clear();
    }

    /** 每个用例一个独立出口：IP 窗活在共享进程里，用例之间不该互相牵连 */
    private String ip() {
        long n = IP_SEQ.incrementAndGet();
        return "10.14." + (n / 250 % 200 + 1) + "." + (n % 250 + 1);
    }

    private String code(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private HttpResponse<String> wxBind(String ticket, String username, String password, String ip)
            throws Exception {
        return call(HttpMethod.POST, WX + "/bind",
                Map.of("bindTicket", ticket, "username", username, "password", password), null, ip);
    }

    private HttpResponse<String> wxRegister(String ticket, String username, String password,
                                            String nickname, String ip) throws Exception {
        Map<String, Object> body = nickname == null
                ? Map.of("bindTicket", ticket, "username", username, "password", password)
                : Map.of("bindTicket", ticket, "username", username, "password", password, "nickname", nickname);
        return call(HttpMethod.POST, WX + "/register", body, null, ip);
    }

    /** 取一张未使用过的票（前提：该微信尚未绑定），并登记以便收尾精确删除 */
    private String ticketFor(String code, String ip) throws Exception {
        JsonNode d = data(call(HttpMethod.POST, WX + "/login", Map.of("code", code), null, ip));
        assertFalse(d.path("bound").asBoolean(), "取票前提是该微信尚未绑定");
        String t = d.path("bindTicket").asText();
        issued.add(t);
        return t;
    }

    private void bindTo(String code, Actor a, String ip) throws Exception {
        data(wxBind(ticketFor(code, ip), a.username(), PWD, ip));
    }

    private HttpResponse<String> call(HttpMethod method, String path, Object body, String jwt, String ip)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (jwt != null) b.header("Authorization", "Bearer " + jwt);
        if (ip != null) b.header("X-Forwarded-For", ip);
        b.method(method.name(), body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(Actor a, String path) throws Exception {
        return data(call(HttpMethod.GET, path, null, a.jwt(), a.ip()));
    }

    private JsonNode data(HttpResponse<String> r) throws Exception {
        assertTrue(r.statusCode() < 400, "响应应为 2xx/3xx: " + brief(r.body()));
        JsonNode node = mapper.readTree(r.body());
        assertEquals(OK, node.path("code").asInt(), "信封码应为 0: " + brief(r.body()));
        return node.path("data");
    }

    private int codeOf(HttpResponse<String> r) throws Exception {
        return mapper.readTree(r.body()).path("code").asInt();
    }

    private String brief(String s) {
        return s == null ? "" : s.substring(0, Math.min(200, s.length()));
    }

    private Actor registerViaWeb(String prefix) throws Exception {
        String username = prefix + System.nanoTime();
        String ip = ip();
        JsonNode d = data(call(HttpMethod.POST, "/api/v1/auth/register",
                Map.of("username", username, "password", PWD, "nickname", "t"), null, ip));
        return new Actor(d.path("accessToken").asText(), d.path("userId").asLong(), username, ip);
    }
}
