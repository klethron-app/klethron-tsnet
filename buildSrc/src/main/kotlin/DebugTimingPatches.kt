import java.io.File
import org.gradle.api.GradleException

// Only called for debug native builds. Release sources, including local Go sources, stay untouched.
fun applyDebugTiming(workspace: File, upstream: File) {
    fun edit(path: String, vararg changes: Pair<String, String>) {
        val file = workspace.resolve(path)
        var text = file.readText()
        for ((old, new) in changes) {
            if (text.split(old).size != 2) {
                throw GradleException("Debug timing patch does not match $path; review DebugTimingPatches.kt and rerun ./gradlew build")
            }
            text = text.replace(old, new)
        }
        file.writeText(text)
    }
    fun patch(path: String, hash: String, vararg changes: Pair<String, String>) {
        if (sha256(upstream.resolve(path).readBytes()) != hash) {
            throw GradleException("Pinned $path changed; review debug timing hooks and rerun ./gradlew build")
        }
        edit("tailscale/$path", "import (" to "import (\n\t\"tailscale.com/util/klethrontiming\"", *changes)
    }
    val helper = workspace.resolve("tailscale/util/klethrontiming/timing.go")
    helper.parentFile.mkdirs()
    helper.writeText("""
        // Debug build-copy instrumentation; never present in production builds.
        package klethrontiming

        import (
            "sync/atomic"
            "tailscale.com/types/logger"
        )

        const Marker = "klethron_timing %s %d %d"
        var sequence atomic.Uint64

        func Start(logf logger.Logf, phase string) (uint64, func()) {
            id := sequence.Add(1)
            if logf != nil { logf(Marker, phase, 0, id) }
            return id, func() { if logf != nil { logf(Marker, phase, 1, id) } }
        }

        func Span(logf logger.Logf, phase string) func() {
            _, done := Start(logf, phase)
            return done
        }

        func Mark(logf logger.Logf, phase string) {
            if logf != nil { logf(Marker, phase, 2, uint64(0)) }
        }
    """.trimIndent() + "\n")
    edit("main.go",
        "n := newNode(newServer())" to "n := newNode(newServer())\n\tconfigureTiming(n)\n\tdefer timingSpan(n, \"native_start\")()",
        "nodes.byID[nodes.last] = n" to "nodes.byID[nodes.last] = n\n\ttimingLink(n, nodes.last)")
    edit("node.go",
        "func (n *node) close() {" to "func (n *node) close() {\n\tdefer timingSpan(n, \"native_close\")()\n\tdefer timingNodes.Delete(n)",
        "\t\tstatus.state = *notify.State" to "\t\tstatus.state = *notify.State\n\t\tif status.state != n.status.state { timingState(n, status.state) }",
        "\tapp := tcpSocket(fd)" to "\tif n != nil { defer timingDial(n, fd)() }\n\tapp := tcpSocket(fd)",
        "\tstatus, _, access := n.wait(ctx, func(s nodeStatus, _ uint64) bool { return s.state == ipn.Running })" to
            "\trunningDone := timingSpan(n, \"running_wait\")\n\tstatus, _, access := n.wait(ctx, func(s nodeStatus, _ uint64) bool { return s.state == ipn.Running })\n\trunningDone()",
        "\taddresses, err := n.server.LookupIPForDial(dialCtx, host)" to
            "\tlookupDone := timingSpan(n, \"peer_lookup\")\n\taddresses, err := n.server.LookupIPForDial(dialCtx, host)\n\tlookupDone()",
        "\tvar conn net.Conn" to "\tvar conn net.Conn\n\ttcpDone := timingSpan(n, \"peer_tcp\")",
        "\tif err != nil {\n\t\treturn n.failed(ctx, resultDialFailed)" to "\ttcpDone()\n\tif err != nil {\n\t\treturn n.failed(ctx, resultDialFailed)")
    edit("store.go",
        "func (s *javaStore) ReadState(key ipn.StateKey) ([]byte, error) {" to
            "func (s *javaStore) ReadState(key ipn.StateKey) ([]byte, error) {\n\tdefer timingSpan(s.node, \"store_read\")()",
        "func (s *javaStore) WriteState(key ipn.StateKey, value []byte) error {" to
            "func (s *javaStore) WriteState(key ipn.StateKey, value []byte) error {\n\tdefer timingSpan(s.node, \"store_write\")()")
    patch("control/controlclient/direct.go", "ccdcc186decb2efe76c98af7e4914b77f15a7d7eda9406b1bb2b441c7fa1575a",
        "func (c *Direct) doLogin(ctx context.Context, opt loginOpt) (mustRegen bool, newURL string, nks tkatype.MarshaledSignature, err error) {" to
            "func (c *Direct) doLogin(ctx context.Context, opt loginOpt) (mustRegen bool, newURL string, nks tkatype.MarshaledSignature, err error) {\n\tdefer klethrontiming.Span(c.logf, \"control_login\")()",
        "\t\tkeys, err := loadServerPubKeys(ctx, c.httpc, c.serverURL)" to
            "\t\tkeysDone := klethrontiming.Span(c.logf, \"control_keys\")\n\t\tkeys, err := loadServerPubKeys(ctx, c.httpc, c.serverURL)\n\t\tkeysDone()",
        "\tts2021.AddLBHeader(req, request.NodeKey)" to
            "\tts2021.AddLBHeader(req, request.NodeKey)\n\tdefer klethrontiming.Span(c.logf, \"control_registration\")()",
        "\tts2021.AddLBHeader(req, nodeKey)" to
            "\tts2021.AddLBHeader(req, nodeKey)\n\tmapDone := klethrontiming.Span(c.logf, \"control_map_request\")\n\tdefer func() { if mapDone != nil { mapDone() } }()",
        "\t\tmetricMapResponseMap.Add(1)" to
            "\t\tmetricMapResponseMap.Add(1)\n\t\tif mapDone != nil {\n\t\t\tmapDone()\n\t\t\tmapDone = nil\n\t\t\tklethrontiming.Mark(c.logf, \"control_map_received\")\n\t\t}")
    patch("control/controlhttp/client.go", "21e8c159a30e2c3afea5d6641116e0ee73df5118f062a24ccc89705d4e0d7ab9",
        "func (a *Dialer) dial(ctx context.Context) (*ClientConn, error) {" to
            "func (a *Dialer) dial(ctx context.Context) (*ClientConn, error) {\n\tdefer klethrontiming.Span(a.logf, \"control_connect\")()",
        "func (a *Dialer) dialURL(ctx context.Context, u *url.URL, optAddr netip.Addr, optACEHost string) (*ClientConn, error) {" to
            "func (a *Dialer) dialURL(ctx context.Context, u *url.URL, optAddr netip.Addr, optACEHost string) (*ClientConn, error) {\n\tphase := \"control_http\"\n\tif u.Scheme == \"https\" { phase = \"control_https\" }\n\tdefer klethrontiming.Span(a.logf, phase)()")
    patch("net/netcheck/netcheck.go", "15857e228e9ba2449ca5cf9bad3223e4c96e593df80d3b8dcb0d40dd54506bcb",
        "func (c *Client) GetReport(ctx context.Context, dm *tailcfg.DERPMap, opts *GetReportOpts) (_ *Report, reterr error) {" to
            "func (c *Client) GetReport(ctx context.Context, dm *tailcfg.DERPMap, opts *GetReportOpts) (_ *Report, reterr error) {\n\tdefer klethrontiming.Span(c.logf, \"netcheck\")()",
        "\tdefer stunTimer.Stop()" to "\tdefer stunTimer.Stop()\n\tstunDone := klethrontiming.Span(c.logf, \"stun_wait\")",
        "\tif !c.SkipExternalNetwork && c.PortMapper != nil {\n\t\trs.waitPortMap.Wait()" to
            "\tstunDone()\n\tif !c.SkipExternalNetwork && c.PortMapper != nil {\n\t\tportmapDone := klethrontiming.Span(c.logf, \"portmap_wait\")\n\t\trs.waitPortMap.Wait()\n\t\tportmapDone()")
    patch("derp/derphttp/derphttp_client.go", "38dbd3eb7b9c55d62e6c3386a5d3ce665defbab2373b454fc8dc31e3953d2ce0",
        "\tc.atomicState.Store(ConnectedState{Connecting: true})" to
            "\tc.atomicState.Store(ConnectedState{Connecting: true})\n\tdefer klethrontiming.Span(c.logf, \"relay_connect\")()")
    patch("wgengine/magicsock/derp.go", "2a733a8eabdcee27b80b7f1e325da1171ced39b7742a56965a41c564bb632a07",
        "    c.setNearestDERPLocked(regionID)" to "    klethrontiming.Mark(c.logf, \"relay_hint\")\n    c.setNearestDERPLocked(regionID)",
        "\tprevious := c.myDerp" to "\tklethrontiming.Mark(c.logf, \"relay_selected\")\n\tprevious := c.myDerp")
    patch("wgengine/magicsock/magicsock.go", "c501a422c108fd57e6e8f2b6b90ec9a07786cd6f0b2d75a3b8b33fd150aafba1",
        "func (c *Conn) rebindAndReset(resetDERP bool) {" to
            "func (c *Conn) rebindAndReset(resetDERP bool) {\n\tdefer klethrontiming.Span(c.logf, \"rebind\")()")
}
