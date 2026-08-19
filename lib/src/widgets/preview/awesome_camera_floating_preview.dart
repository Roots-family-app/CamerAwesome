import 'package:camerawesome/pigeon.dart';
import 'package:camerawesome/src/orchestrator/models/sensors.dart';
import 'package:camerawesome/src/widgets/preview/awesome_preview_fit.dart';
import 'package:camerawesome/src/widgets/widgets.dart';
import 'package:flutter/material.dart';

class AwesomeCameraFloatingPreview extends StatefulWidget {
  final Texture texture;
  final int index;
  final double aspectRatio;
  final Sensor sensor;
  final PictureInPictureConfig pictureInPictureConfig;

  /// LOCAL PATCH (roots): the real stream size of THIS sensor, as reported by
  /// `CameraState.previewSize(index)`. Upstream had no way to know it (see the
  /// `FIXME` this replaces) and invented `1000 x 1000/aspectRatio` instead, so
  /// the PiP scaled a 4:3 stream as if it were whatever ratio the config asked
  /// for. Null falls back to the old invented size so the widget still lays out
  /// if the native side cannot answer.
  ///
  /// Upstream fix: plumb the per-sensor preview size through here rather than
  /// guessing, which is what the index-aware `getEffectivPreviewSize` on the
  /// native side now makes possible.
  final PreviewSize? previewSize;

  AwesomeCameraFloatingPreview({
    super.key,
    required this.index,
    required this.sensor,
    required this.texture,
    required this.aspectRatio,
    this.previewSize,
    PictureInPictureConfig? pictureInPictureConfig,
  }) : pictureInPictureConfig =
            pictureInPictureConfig ?? PictureInPictureConfig(sensor: sensor);

  @override
  State<AwesomeCameraFloatingPreview> createState() =>
      _AwesomeCameraFloatingPreviewState();
}

class _AwesomeCameraFloatingPreviewState
    extends State<AwesomeCameraFloatingPreview> {
  late Offset _position;

  /// LOCAL PATCH (roots): the sensor's real size when the native side could
  /// report one, and upstream's invented size when it could not.
  PreviewSize get _previewSize =>
      widget.previewSize ??
      PreviewSize(width: 1000, height: 1000 / widget.aspectRatio);

  @override
  void initState() {
    super.initState();
    _position = widget.pictureInPictureConfig.startingPosition;
  }

  @override
  Widget build(BuildContext context) {
    return Positioned(
      left: _position.dx,
      top: _position.dy,
      child: AwesomeBouncingWidget(
        // TODO We can tap behind the preview with the current AwesomeBouncingWidget implementation
        onTap: widget.pictureInPictureConfig.onTap,
        disabledOpacity: 1.0,
        child: GestureDetector(
          onPanUpdate: widget.pictureInPictureConfig.isDraggable
              ? (details) {
                  setState(() {
                    _position = Offset(
                      _position.dx + details.delta.dx,
                      _position.dy + details.delta.dy,
                    );
                  });
                }
              : null,
          // LOCAL PATCH (roots): use the sensor's REAL preview size, and the
          // REAL constraints of the box pictureInPictureBuilder puts the
          // preview in. Upstream passed an invented 1000-wide size and a
          // hardcoded 300x300 BoxConstraints, so the fit maths ran against two
          // numbers that had nothing to do with either the stream or the box it
          // was drawn into. The LayoutBuilder has to sit INSIDE the builder's
          // result -- the Positioned above hands down unbounded constraints,
          // and it is the builder's own SizedBox that makes them tight.
          //
          // The aspect ratio handed to the builder is the real one too, so a
          // caller sizing its PiP box from it gets a box the preview fills
          // exactly instead of one it has to crop into.
          child: widget.pictureInPictureConfig.pictureInPictureBuilder(
            LayoutBuilder(
              builder: (context, constraints) => AnimatedPreviewFit(
                previewFit: CameraPreviewFit.cover,
                previewSize: _previewSize,
                constraints:
                    constraints.hasBoundedWidth && constraints.hasBoundedHeight
                        ? constraints
                        : const BoxConstraints(maxWidth: 300, maxHeight: 300),
                sensor: widget.sensor,
                child: widget.texture,
              ),
            ),
            _previewSize.width / _previewSize.height,
          ),
        ),
      ),
    );
  }
}
