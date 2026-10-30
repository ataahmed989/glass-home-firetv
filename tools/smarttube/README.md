# SmartTube tile source

`icon-src/org.smarttube.stable.png` is YouTube's tile (`icon-src/com.amazon.firetv.youtube.png`) on
black, its play button in YouTube's exact shape but blue (#2B7FDA), and "SmartTube" in white Fjalla One
(Google Fonts, SIL OFL), each letter the same distance from the next, at the height of YouTube's wordmark.
The font is only used to draw the picture; Glass Home does not include it.

    python3 wordmark.py ../icon-src/com.amazon.firetv.youtube.png FjallaOne-Regular.ttf wordmark.png 0.045
    python3 tile_src.py ../icon-src/com.amazon.firetv.youtube.png wordmark.png 2B7FDA ../icon-src/org.smarttube.stable.png

Then `make_layers.py` builds its layers, as it does YouTube's.
