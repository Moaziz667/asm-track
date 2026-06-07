import 'package:flutter/material.dart';
import 'package:phosphor_flutter/phosphor_flutter.dart';

class SwipeButton extends StatefulWidget {
  const SwipeButton({
    super.key,
    required this.label,
    required this.onSwipe,
    this.isWorking = false,
    this.icon = PhosphorIconsBold.caretDoubleRight,
    this.activeColor,
  });

  final String label;
  final VoidCallback? onSwipe;
  final bool isWorking;
  final IconData icon;
  final Color? activeColor;

  @override
  State<SwipeButton> createState() => _SwipeButtonState();
}

class _SwipeButtonState extends State<SwipeButton> with SingleTickerProviderStateMixin {
  double _position = 0.0;
  bool _completed = false;

  late final AnimationController _controller;
  late final Animation<double> _animation;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 200),
    );
    _animation = Tween<double>(begin: 0.0, end: 0.0).animate(_controller);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant SwipeButton oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.isWorking && oldWidget.isWorking) {
      _reset();
    }
  }

  void _reset() {
    setState(() {
      _position = 0.0;
      _completed = false;
    });
    _controller.reverse();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final primaryColor = widget.activeColor ?? cs.primary;
    final isDisabled = widget.onSwipe == null || widget.isWorking;

    return LayoutBuilder(
      builder: (context, constraints) {
        final double trackWidth = constraints.maxWidth;
        const double buttonHeight = 56.0;
        const double padding = 4.0;
        const double thumbSize = buttonHeight - (padding * 2); // 48.0
        final double maxPosition = trackWidth - thumbSize - (padding * 2);

        _animation = Tween<double>(begin: _position, end: 0.0).animate(
          CurvedAnimation(parent: _controller, curve: Curves.easeOut),
        )..addListener(() {
            setState(() {
              _position = _animation.value;
            });
          });

        return Container(
          height: buttonHeight,
          width: trackWidth,
          decoration: BoxDecoration(
            color: isDisabled 
                ? cs.surfaceContainerHighest.withAlpha(128) 
                : primaryColor.withAlpha(20),
            borderRadius: BorderRadius.circular(28),
            border: Border.all(
              color: isDisabled 
                  ? cs.outlineVariant.withAlpha(128) 
                  : primaryColor.withAlpha(51),
              width: 1.5,
            ),
          ),
          child: Stack(
            children: [
              Positioned(
                left: 0,
                top: 0,
                bottom: 0,
                child: Container(
                  width: _position + thumbSize + padding * 2,
                  decoration: BoxDecoration(
                    color: isDisabled 
                        ? Colors.transparent 
                        : primaryColor.withAlpha(38),
                    borderRadius: BorderRadius.horizontal(
                      left: const Radius.circular(28),
                      right: Radius.circular(_position > maxPosition * 0.9 ? 28 : 14),
                    ),
                  ),
                ),
              ),
              Center(
                child: Padding(
                  padding: const EdgeInsets.only(left: 32, right: 8),
                  child: Text(
                    widget.label,
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                      color: isDisabled 
                          ? cs.onSurfaceVariant.withAlpha(128) 
                          : primaryColor,
                      letterSpacing: 0.3,
                    ),
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
              Positioned(
                left: padding + _position,
                top: padding,
                bottom: padding,
                child: GestureDetector(
                  onHorizontalDragUpdate: isDisabled || _completed
                      ? null
                      : (details) {
                          setState(() {
                            _position += details.primaryDelta!;
                            if (_position < 0.0) _position = 0.0;
                            if (_position > maxPosition) _position = maxPosition;
                          });
                        },
                  onHorizontalDragEnd: isDisabled || _completed
                      ? null
                      : (details) {
                          if (_position > maxPosition * 0.85) {
                            setState(() {
                              _position = maxPosition;
                              _completed = true;
                            });
                            if (widget.onSwipe != null) {
                              widget.onSwipe!();
                            }
                          } else {
                            _controller.value = _position / maxPosition;
                            _controller.reverse();
                          }
                        },
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 150),
                    width: thumbSize,
                    height: thumbSize,
                    decoration: BoxDecoration(
                      color: isDisabled ? cs.surfaceContainerHighest : primaryColor,
                      shape: BoxShape.circle,
                      boxShadow: [
                        if (!isDisabled)
                          BoxShadow(
                            color: primaryColor.withAlpha(76),
                            blurRadius: 8,
                            offset: const Offset(0, 3),
                          ),
                      ],
                    ),
                    child: Center(
                      child: widget.isWorking
                          ? SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(
                                strokeWidth: 2.5,
                                color: cs.onPrimary,
                              ),
                            )
                          : Icon(
                              widget.icon,
                              color: isDisabled ? cs.onSurfaceVariant : cs.onPrimary,
                              size: 20,
                            ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
