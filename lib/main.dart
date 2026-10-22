import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      systemNavigationBarColor: Colors.transparent,
    ),
  );
  runApp(const FingerControlerApp());
}

class FingerControlerApp extends StatelessWidget {
  const FingerControlerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Finger Controler',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.system,
      theme: _buildTheme(Brightness.light),
      darkTheme: _buildTheme(Brightness.dark),
      home: const MainControlScreen(),
    );
  }

  ThemeData _buildTheme(Brightness brightness) {
    final isDark = brightness == Brightness.dark;
    final bg = isDark ? const Color(0xFF191918) : const Color(0xFFF4F3EE);
    final surface = isDark ? const Color(0xFF232220) : const Color(0xFFECEAE4);

    return ThemeData(
      brightness: brightness,
      scaffoldBackgroundColor: bg,
      cardColor: surface,
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xFFD97757),
        brightness: brightness,
        surface: surface,
      ),
    );
  }
}

class MainControlScreen extends StatefulWidget {
  const MainControlScreen({super.key});

  @override
  State<MainControlScreen> createState() => _MainControlScreenState();
}

class _MainControlScreenState extends State<MainControlScreen>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  static const MethodChannel _engineChannel =
      MethodChannel('com.fingercontroler/engine');

  bool _cameraGranted = false;
  bool _overlayGranted = false;
  bool _accessibilityGranted = false;
  bool _isEngineRunning = false;
  bool _isLoading = false;

  late AnimationController _pulseController;
  late Animation<double> _pulseAnimation;
  late Animation<double> _glowAnimation;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2200),
    )..repeat(reverse: true);

    _pulseAnimation = Tween<double>(begin: 0.96, end: 1.04).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );

    _glowAnimation = Tween<double>(begin: 0.15, end: 0.38).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );

    _refreshState();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pulseController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshState();
    }
  }

  Future<void> _refreshState() async {
    try {
      final dynamic rawStatus =
          await _engineChannel.invokeMethod('getPermissionsStatus');
      if (rawStatus is Map) {
        final Map<dynamic, dynamic> status = rawStatus;
        final bool running =
            await _engineChannel.invokeMethod<bool>('isEngineRunning') ?? false;

        if (mounted) {
          setState(() {
            _cameraGranted = status['camera'] == true;
            _overlayGranted = status['overlay'] == true;
            _accessibilityGranted = status['accessibility'] == true;
            _isEngineRunning = running;
          });
        }
      }
    } catch (e) {
      debugPrint('Error updating state: $e');
    }
  }

  bool get _allPermissionsGranted =>
      _cameraGranted && _overlayGranted && _accessibilityGranted;

  Future<void> _toggleEngine() async {
    if (!_allPermissionsGranted) {
      HapticFeedback.heavyImpact();
      // Prompt first ungranted permission
      if (!_cameraGranted) {
        await _requestCamera();
      } else if (!_overlayGranted) {
        await _openOverlay();
      } else if (!_accessibilityGranted) {
        await _openAccessibility();
      }
      return;
    }

    setState(() => _isLoading = true);
    HapticFeedback.mediumImpact();

    try {
      if (_isEngineRunning) {
        await _engineChannel.invokeMethod('stopEngine');
        setState(() => _isEngineRunning = false);
      } else {
        await _engineChannel.invokeMethod('startEngine');
        setState(() => _isEngineRunning = true);
      }
      await Future.delayed(const Duration(milliseconds: 200));
      await _refreshState();
    } catch (e) {
      debugPrint('Toggle engine failed: $e');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _requestCamera() async {
    HapticFeedback.selectionClick();
    try {
      await _engineChannel.invokeMethod('requestCameraPermission');
      await Future.delayed(const Duration(milliseconds: 500));
      await _refreshState();
    } catch (e) {
      debugPrint('Camera request failed: $e');
    }
  }

  Future<void> _openOverlay() async {
    HapticFeedback.selectionClick();
    try {
      await _engineChannel.invokeMethod('openOverlaySettings');
    } catch (e) {
      debugPrint('Overlay settings failed: $e');
    }
  }

  Future<void> _openAccessibility() async {
    HapticFeedback.selectionClick();
    try {
      await _engineChannel.invokeMethod('openAccessibilitySettings');
    } catch (e) {
      debugPrint('Accessibility settings failed: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDark = Theme.of(context).brightness == Brightness.dark;
    final primaryTerracotta = const Color(0xFFD97757);
    final textMuted = isDark
        ? const Color(0xFF8A8884)
        : const Color(0xFF7E7C76);
    final textPrimary = isDark
        ? const Color(0xFFECEAE4)
        : const Color(0xFF232220);

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 20.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              const SizedBox(height: 18),
              // Subtle, refined Claude-style serif title
              Text(
                'Finger Controler',
                style: GoogleFonts.newsreader(
                  textStyle: TextStyle(
                    fontFamily: 'serif',
                    fontSize: 27,
                    fontWeight: FontWeight.w400,
                    letterSpacing: -0.3,
                    color: textPrimary,
                  ),
                ),
              ),
              const SizedBox(height: 6),
              Text(
                _isEngineRunning ? 'Tracking Active' : 'Standby',
                style: GoogleFonts.newsreader(
                  textStyle: TextStyle(
                    fontFamily: 'serif',
                    fontSize: 15,
                    fontStyle: FontStyle.italic,
                    color: _isEngineRunning ? primaryTerracotta : textMuted,
                  ),
                ),
              ),

              const Spacer(),

              // Centered Breathing Status Orb / Button
              Center(
                child: AnimatedBuilder(
                  animation: _pulseController,
                  builder: (context, child) {
                    final scale = _isEngineRunning ? _pulseAnimation.value : 1.0;
                    final glowAlpha =
                        _isEngineRunning ? _glowAnimation.value : 0.08;

                    return Transform.scale(
                      scale: scale,
                      child: GestureDetector(
                        onTap: _isLoading ? null : _toggleEngine,
                        child: Container(
                          width: 172,
                          height: 172,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: _isEngineRunning
                                ? primaryTerracotta
                                : (isDark
                                    ? const Color(0xFF262523)
                                    : const Color(0xFFECEAE4)),
                            border: Border.all(
                              color: _isEngineRunning
                                  ? primaryTerracotta.withValues(alpha: 0.9)
                                  : (isDark
                                      ? const Color(0xFF383733)
                                      : const Color(0xFFDEDCD5)),
                              width: 1.5,
                            ),
                            boxShadow: [
                              BoxShadow(
                                color: primaryTerracotta.withValues(alpha: glowAlpha),
                                blurRadius: _isEngineRunning ? 36 : 14,
                                spreadRadius: _isEngineRunning ? 8 : 1,
                              ),
                            ],
                          ),
                          child: Center(
                            child: _isLoading
                                ? SizedBox(
                                    width: 32,
                                    height: 32,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2.2,
                                      valueColor: AlwaysStoppedAnimation<Color>(
                                        _isEngineRunning
                                            ? Colors.white
                                            : primaryTerracotta,
                                      ),
                                    ),
                                  )
                                : Icon(
                                    _isEngineRunning
                                        ? Icons.power_settings_new_rounded
                                        : Icons.touch_app_outlined,
                                    size: 48,
                                    color: _isEngineRunning
                                        ? Colors.white
                                        : primaryTerracotta,
                                  ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),

              const Spacer(),

              // Minimalist status pills at the bottom
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                decoration: BoxDecoration(
                  color: isDark
                      ? const Color(0xFF232220)
                      : const Color(0xFFECEAE4),
                  borderRadius: BorderRadius.circular(30),
                  border: Border.all(
                    color: isDark
                        ? const Color(0xFF33322F)
                        : const Color(0xFFDEDCD5),
                    width: 1,
                  ),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _buildStatusPill(
                      label: 'Camera',
                      granted: _cameraGranted,
                      onTap: _requestCamera,
                      isDark: isDark,
                      accentColor: primaryTerracotta,
                    ),
                    const SizedBox(width: 6),
                    _buildStatusPill(
                      label: 'Overlay',
                      granted: _overlayGranted,
                      onTap: _openOverlay,
                      isDark: isDark,
                      accentColor: primaryTerracotta,
                    ),
                    const SizedBox(width: 6),
                    _buildStatusPill(
                      label: 'Accessibility',
                      granted: _accessibilityGranted,
                      onTap: _openAccessibility,
                      isDark: isDark,
                      accentColor: primaryTerracotta,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 14),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildStatusPill({
    required String label,
    required bool granted,
    required VoidCallback onTap,
    required bool isDark,
    required Color accentColor,
  }) {
    return InkWell(
      onTap: granted ? null : onTap,
      borderRadius: BorderRadius.circular(20),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
        decoration: BoxDecoration(
          color: granted
              ? accentColor.withValues(alpha: isDark ? 0.22 : 0.12)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 7,
              height: 7,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: granted
                    ? accentColor
                    : (isDark
                        ? const Color(0xFF555450)
                        : const Color(0xFFBBB9B2)),
                boxShadow: granted
                    ? [
                        BoxShadow(
                          color: accentColor.withValues(alpha: 0.5),
                          blurRadius: 4,
                          spreadRadius: 1,
                        ),
                      ]
                    : null,
              ),
            ),
            const SizedBox(width: 6),
            Text(
              label,
              style: GoogleFonts.inter(
                textStyle: TextStyle(
                  fontFamily: 'sans-serif',
                  fontSize: 12,
                  fontWeight: granted ? FontWeight.w600 : FontWeight.w400,
                  letterSpacing: 0.1,
                  color: granted
                      ? (isDark ? const Color(0xFFECEAE4) : const Color(0xFF232220))
                      : (isDark
                          ? const Color(0xFF8A8884)
                          : const Color(0xFF7E7C76)),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
