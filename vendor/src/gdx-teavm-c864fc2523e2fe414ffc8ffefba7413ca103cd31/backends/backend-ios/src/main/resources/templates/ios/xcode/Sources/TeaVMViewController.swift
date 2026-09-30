import GLKit
import UIKit

final class TeaVMViewController: UIViewController, GLKViewDelegate {
    private let maxPointers = 20
    private let statusLabel = UILabel()
    private var glContext: EAGLContext!
    private var glView: GLKView {
        view as! GLKView
    }
    private var displayLink: CADisplayLink?
    private var started = false
    private var touchPointers: [ObjectIdentifier: Int32] = [:]
    private var availablePointers: [Int32] = Array(0..<20).map(Int32.init)

    override func loadView() {
        guard let context = EAGLContext(api: .openGLES3) ?? EAGLContext(api: .openGLES2) else {
            fatalError("Unable to create OpenGL ES context")
        }
        glContext = context
        EAGLContext.setCurrent(context)

        let glView = GLKView(frame: UIScreen.main.bounds, context: context)
        glView.drawableColorFormat = .RGBA8888
        glView.drawableDepthFormat = .format16
        glView.drawableStencilFormat = .format8
        glView.enableSetNeedsDisplay = false
        glView.delegate = self
        view = glView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.isMultipleTouchEnabled = true
        setupStatusLabel()
        startTeaVM()
        resizeTeaVM()

        let displayLink = CADisplayLink(target: self, selector: #selector(renderFrame))
        displayLink.add(to: .main, forMode: .common)
        self.displayLink = displayLink
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        resizeTeaVM()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        gdx_teavm_ios_resume()
        displayLink?.isPaused = false
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        displayLink?.isPaused = true
        gdx_teavm_ios_pause()
    }

    deinit {
        displayLink?.invalidate()
        gdx_teavm_ios_dispose()
        if EAGLContext.current() === glContext {
            EAGLContext.setCurrent(nil)
        }
    }

    override var prefersStatusBarHidden: Bool {
        true
    }

    private func startTeaVM() {
        guard !started else {
            return
        }
        started = true
        statusLabel.text = "Starting TeaVM..."
        let workingDirectory = Bundle.main.resourcePath.map { "\($0)/assets" }
            ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.path
            ?? NSHomeDirectory()
        workingDirectory.withCString { path in
            _ = gdx_teavm_ios_start(path)
        }
        statusLabel.text = "TeaVM running"
    }

    private func setupStatusLabel() {
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.text = "TeaVM loading"
        statusLabel.textColor = .white
        statusLabel.font = UIFont.monospacedSystemFont(ofSize: 18, weight: .semibold)
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        view.addSubview(statusLabel)
        NSLayoutConstraint.activate([
            statusLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            statusLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            statusLabel.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -24)
        ])
    }

    private func resizeTeaVM() {
        let scale = glView.contentScaleFactor
        let pixelWidth = Int32(glView.drawableWidth)
        let pixelHeight = Int32(glView.drawableHeight)
        gdx_teavm_ios_resize(pixelWidth, pixelHeight, Float(scale))
    }

    @objc private func renderFrame() {
        glView.display()
    }

    func glkView(_ view: GLKView, drawIn rect: CGRect) {
        EAGLContext.setCurrent(glContext)
        resizeTeaVM()
        gdx_teavm_ios_set_viewport(Int32(view.drawableWidth), Int32(view.drawableHeight))
        gdx_teavm_ios_render()
        updateStatusLabel()
    }

    private func updateStatusLabel() {
        switch gdx_teavm_ios_status_code() {
        case let code where code < 0:
            statusLabel.isHidden = false
            statusLabel.text = "TeaVM error \(abs(code))"
        case 1:
            statusLabel.isHidden = false
            statusLabel.text = "TeaVM created"
        case 2:
            statusLabel.isHidden = true
        default:
            break
        }
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        sendTouches(touches, type: 0, releasePointers: false)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        sendTouches(touches, type: 2, releasePointers: false)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        sendTouches(touches, type: 1, releasePointers: true)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        sendTouches(touches, type: 3, releasePointers: true)
    }

    private func sendTouches(_ touches: Set<UITouch>, type: Int32, releasePointers: Bool) {
        let scale = view.window?.screen.scale ?? UIScreen.main.scale
        for touch in touches {
            let pointer = pointerIndex(for: touch)
            let location = touch.location(in: view)
            let pressure = normalizedPressure(for: touch)
            gdx_teavm_ios_touch(
                type,
                pointer,
                Int32((location.x * scale).rounded()),
                Int32((location.y * scale).rounded()),
                pressure
            )
            if releasePointers {
                releasePointer(for: touch)
            }
        }
    }

    private func pointerIndex(for touch: UITouch) -> Int32 {
        let id = ObjectIdentifier(touch)
        if let existing = touchPointers[id] {
            return existing
        }
        let pointer = availablePointers.isEmpty ? Int32(touchPointers.count % maxPointers) : availablePointers.removeFirst()
        touchPointers[id] = pointer
        return pointer
    }

    private func releasePointer(for touch: UITouch) {
        let id = ObjectIdentifier(touch)
        guard let pointer = touchPointers.removeValue(forKey: id) else {
            return
        }
        availablePointers.append(pointer)
        availablePointers.sort()
    }

    private func normalizedPressure(for touch: UITouch) -> Float {
        if touch.maximumPossibleForce > 0 {
            return Float(touch.force / touch.maximumPossibleForce)
        }
        return 1
    }
}
