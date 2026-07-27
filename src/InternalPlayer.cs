using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Linq;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;
using NAudio.CoreAudioApi;
using NAudio.Dsp;
using NAudio.Wave;
using NAudio.Wave.SampleProviders;

namespace AudioPersonal
{
    sealed class InternalPlayerEngine : IDisposable
    {
        readonly object gate = new object();
        readonly List<TrackInfo> playlist = new List<TrackInfo>();
        readonly Random random = new Random();
        static readonly string DefaultPlaylist = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AudioPersonal", "playlist-actual.m3u8");
        IWavePlayer output;
        WaveStream reader;
        DspSampleProvider dsp;
        int currentIndex = -1;
        bool manualStop, disposed, shuffle;
        float leftPeak, rightPeak;
        readonly float[] eqGains = new float[10];
        bool eqEnabled = true, megaBass, crystalTreble;

        public event EventHandler StateChanged;
        public event EventHandler PlaylistChanged;
        public event EventHandler<string> Error;

        public InternalPlayerEngine() { ThreadPool.QueueUserWorkItem(delegate { LoadPlaylist(DefaultPlaylist, false); }); }

        public IList<TrackInfo> PlaylistSnapshot
        {
            get { lock (gate) return playlist.ToArray(); }
        }

        public int CurrentIndex { get { lock (gate) return currentIndex; } }
        public string CurrentTitle { get { lock (gate) return currentIndex >= 0 && currentIndex < playlist.Count ? playlist[currentIndex].DisplayName : "Sin reproducción"; } }
        public bool IsPlaying { get { lock (gate) return output != null && output.PlaybackState == PlaybackState.Playing; } }
        public bool IsPaused { get { lock (gate) return output != null && output.PlaybackState == PlaybackState.Paused; } }
        public bool Shuffle { get { lock (gate) return shuffle; } set { lock (gate) shuffle = value; RaiseState(); } }
        public bool EqualizerEnabled { get { lock (gate) return eqEnabled; } set { lock (gate) { eqEnabled = value; ApplyDsp(); } RaiseState(); } }
        public bool MegaBass { get { lock (gate) return megaBass; } set { lock (gate) { megaBass = value; ApplyDsp(); } RaiseState(); } }
        public bool CrystalTreble { get { lock (gate) return crystalTreble; } set { lock (gate) { crystalTreble = value; ApplyDsp(); } RaiseState(); } }

        public TimeSpan Position
        {
            get { lock (gate) { try { return reader == null ? TimeSpan.Zero : reader.CurrentTime; } catch { return TimeSpan.Zero; } } }
        }

        public TimeSpan Duration
        {
            get { lock (gate) { try { return reader == null ? TimeSpan.Zero : reader.TotalTime; } catch { return TimeSpan.Zero; } } }
        }

        public void GetLevels(out float left, out float right) { left = leftPeak; right = rightPeak; leftPeak *= 0.82f; rightPeak *= 0.82f; }
        public float GetEqGain(int index) { lock (gate) return index >= 0 && index < eqGains.Length ? eqGains[index] : 0; }

        public void SetEqGain(int index, float gain)
        {
            lock (gate)
            {
                if (index < 0 || index >= eqGains.Length) return;
                eqGains[index] = Math.Max(-12, Math.Min(12, gain)); ApplyDsp();
            }
        }

        public void ResetEqualizer()
        {
            lock (gate) { Array.Clear(eqGains, 0, eqGains.Length); megaBass = crystalTreble = false; eqEnabled = true; ApplyDsp(); }
            RaiseState();
        }

        public bool Play(TrackInfo track)
        {
            if (track == null || !File.Exists(track.Path)) { RaiseError("La canción ya no existe en el disco."); return false; }
            int index;
            lock (gate)
            {
                index = playlist.FindIndex(delegate(TrackInfo item) { return String.Equals(item.Path, track.Path, StringComparison.OrdinalIgnoreCase); });
                if (index < 0) { playlist.Add(track); index = playlist.Count - 1; SaveDefault(); RaisePlaylist(); }
            }
            return PlayIndex(index);
        }

        public bool PlayIndex(int index)
        {
            TrackInfo track;
            lock (gate)
            {
                if (index < 0 || index >= playlist.Count) return false;
                track = playlist[index];
            }
            if (!File.Exists(track.Path)) { RaiseError("No existe: " + track.Path); return false; }
            try
            {
                lock (gate)
                {
                    StopInternal();
                    reader = OpenAudioReader(track.Path);
                    ISampleProvider samples = reader as ISampleProvider;
                    if (samples == null) samples = reader.ToSampleProvider();
                    dsp = new DspSampleProvider(samples, reader, eqGains, eqEnabled, megaBass, crystalTreble);
                    MeteringSampleProvider metering = new MeteringSampleProvider(dsp, Math.Max(256, dsp.WaveFormat.SampleRate / 20));
                    metering.StreamVolume += MeteringVolume;
                    output = new WasapiOut(AudioClientShareMode.Shared, true, 100);
                    output.PlaybackStopped += PlaybackStopped;
                    output.Init(metering);
                    currentIndex = index; manualStop = false; leftPeak = rightPeak = 0;
                    output.Play();
                }
                RaiseState();
                return true;
            }
            catch (Exception ex)
            {
                lock (gate) StopInternal();
                RaiseError("No se pudo reproducir el archivo: " + ex.Message);
                return false;
            }
        }

        static WaveStream OpenAudioReader(string path)
        {
            try { return new AudioFileReader(path); }
            catch
            {
                try { return new MediaFoundationReader(path); }
                catch (Exception error) { throw new InvalidOperationException("El archivo utiliza una codificación que Audio Personal y Windows no pudieron abrir.", error); }
            }
        }

        public void TogglePlayPause()
        {
            lock (gate)
            {
                if (output == null) { if (playlist.Count > 0) ThreadPool.QueueUserWorkItem(delegate { PlayIndex(currentIndex >= 0 ? currentIndex : 0); }); return; }
                if (output.PlaybackState == PlaybackState.Playing) output.Pause(); else output.Play();
            }
            RaiseState();
        }

        public void Stop()
        {
            lock (gate) { manualStop = true; if (output != null) output.Stop(); if (reader != null) reader.Position = 0; leftPeak = rightPeak = 0; }
            RaiseState();
        }

        public void Next()
        {
            int next;
            lock (gate)
            {
                if (playlist.Count == 0) return;
                next = shuffle && playlist.Count > 1 ? RandomDifferent(currentIndex, playlist.Count) : (currentIndex + 1 + playlist.Count) % playlist.Count;
            }
            PlayIndex(next);
        }

        public void Previous()
        {
            int previous;
            lock (gate)
            {
                if (playlist.Count == 0) return;
                if (reader != null && reader.CurrentTime.TotalSeconds > 4) { reader.Position = 0; return; }
                previous = (currentIndex - 1 + playlist.Count) % playlist.Count;
            }
            PlayIndex(previous);
        }

        public void Seek(double fraction)
        {
            lock (gate)
            {
                if (reader == null) return;
                fraction = Math.Max(0, Math.Min(1, fraction));
                reader.CurrentTime = TimeSpan.FromTicks((long)(reader.TotalTime.Ticks * fraction));
                if (dsp != null) dsp.ResetFilters();
            }
            RaiseState();
        }

        public void AddFiles(IEnumerable<string> files)
        {
            HashSet<string> existing;lock(gate)existing=new HashSet<string>(playlist.Select(delegate(TrackInfo item){return item.Path;}),StringComparer.OrdinalIgnoreCase);
            List<TrackInfo> additions=new List<TrackInfo>();
            foreach(string file in files)
            {
                if(!MusicFiles.IsSupported(file)||!File.Exists(file)||!existing.Add(file))continue;
                try{string artist,title;Mp3Tags.Read(file,out artist,out title);additions.Add(new TrackInfo(file,artist,title));}catch{}
            }
            if(additions.Count==0)return;
            lock (gate)
            {
                foreach(TrackInfo track in additions)if(!playlist.Any(delegate(TrackInfo item){return String.Equals(item.Path,track.Path,StringComparison.OrdinalIgnoreCase);}))playlist.Add(track);
                SaveDefault();
            }
            RaisePlaylist();
        }

        public void RemoveAt(int index)
        {
            lock (gate)
            {
                if (index < 0 || index >= playlist.Count) return;
                bool current = index == currentIndex;
                playlist.RemoveAt(index);
                if (current) { manualStop = true; StopInternal(); currentIndex = -1; }
                else if (index < currentIndex) currentIndex--;
                SaveDefault();
            }
            RaisePlaylist(); RaiseState();
        }

        public void ClearPlaylist()
        {
            lock (gate) { manualStop = true; StopInternal(); playlist.Clear(); currentIndex = -1; SaveDefault(); }
            RaisePlaylist(); RaiseState();
        }

        public void SavePlaylist(string file)
        {
            lock (gate)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(file));
                using (StreamWriter writer = new StreamWriter(file, false, new System.Text.UTF8Encoding(false)))
                {
                    writer.WriteLine("#EXTM3U");
                    foreach (TrackInfo track in playlist) writer.WriteLine(track.Path);
                }
            }
        }

        public bool AppendTrackToPlaylist(string file, TrackInfo track)
        {
            if (String.IsNullOrEmpty(file) || track == null || String.IsNullOrEmpty(track.Path)) return false;
            List<string> lines = File.Exists(file) ? File.ReadAllLines(file).ToList() : new List<string>();
            foreach (string line in lines)
                if (!String.IsNullOrWhiteSpace(line) && !line.StartsWith("#") && String.Equals(line.Trim(), track.Path, StringComparison.OrdinalIgnoreCase)) return false;
            if (!lines.Any(delegate(string line) { return line.Trim().Equals("#EXTM3U", StringComparison.OrdinalIgnoreCase); })) lines.Insert(0, "#EXTM3U");
            lines.Add(track.Path);
            Directory.CreateDirectory(Path.GetDirectoryName(file));
            File.WriteAllLines(file, lines.ToArray(), new System.Text.UTF8Encoding(false));
            return true;
        }

        public void LoadPlaylist(string file, bool notify)
        {
            if (!File.Exists(file)) return;
            try
            {
                List<string> files = new List<string>();
                foreach (string line in File.ReadAllLines(file)) if (!String.IsNullOrWhiteSpace(line) && !line.StartsWith("#")) files.Add(line.Trim());
                lock (gate) { playlist.Clear(); }
                AddFiles(files);
                if (notify) { SaveDefault(); RaisePlaylist(); }
            }
            catch (Exception ex) { if (notify) RaiseError("No se pudo abrir la playlist: " + ex.Message); }
        }

        void SaveDefault()
        {
            try { SavePlaylist(DefaultPlaylist); } catch { }
        }

        void ApplyDsp() { if (dsp != null) dsp.Update(eqGains, eqEnabled, megaBass, crystalTreble); }

        void MeteringVolume(object sender, StreamVolumeEventArgs e)
        {
            if (e.MaxSampleValues == null || e.MaxSampleValues.Length == 0) return;
            leftPeak = Math.Max(leftPeak, Math.Min(1, e.MaxSampleValues[0]));
            rightPeak = Math.Max(rightPeak, Math.Min(1, e.MaxSampleValues.Length > 1 ? e.MaxSampleValues[1] : e.MaxSampleValues[0]));
        }

        void PlaybackStopped(object sender, StoppedEventArgs e)
        {
            if (!Object.ReferenceEquals(sender, output)) return;
            bool advance = !disposed && !manualStop && e.Exception == null && reader != null && reader.Position >= reader.Length - Math.Max(4096, reader.WaveFormat.AverageBytesPerSecond / 2);
            if (e.Exception != null) RaiseError("La reproducción se detuvo: " + e.Exception.Message);
            RaiseState();
            if (advance) ThreadPool.QueueUserWorkItem(delegate { Next(); });
        }

        void StopInternal()
        {
            manualStop = true;
            IWavePlayer oldOutput = output; output = null;
            WaveStream oldReader = reader; reader = null; dsp = null;
            if (oldOutput != null) { try { oldOutput.Stop(); } catch { } try { oldOutput.Dispose(); } catch { } }
            if (oldReader != null) try { oldReader.Dispose(); } catch { }
        }

        int RandomDifferent(int current, int count) { int result; do result = random.Next(count); while (result == current && count > 1); return result; }
        void RaiseState() { EventHandler handler = StateChanged; if (handler != null) handler(this, EventArgs.Empty); }
        void RaisePlaylist() { EventHandler handler = PlaylistChanged; if (handler != null) handler(this, EventArgs.Empty); }
        void RaiseError(string message) { EventHandler<string> handler = Error; if (handler != null) handler(this, message); }

        public void Dispose()
        {
            lock (gate) { disposed = true; StopInternal(); }
        }
    }

    static class MusicFiles
    {
        static readonly HashSet<string> Extensions = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
        { ".mp3", ".flac", ".wav", ".aiff", ".aif", ".m4a", ".ogg", ".mp4", ".wma", ".aac", ".opus" };
        public static bool IsSupported(string file) { try { return Extensions.Contains(Path.GetExtension(file)); } catch { return false; } }

        public static IEnumerable<string> Enumerate(string root)
        {
            Stack<string> pending = new Stack<string>(); pending.Push(root);
            while (pending.Count > 0)
            {
                string directory = pending.Pop(); string[] children, files;
                try { children = Directory.GetDirectories(directory); files = Directory.GetFiles(directory); }
                catch { continue; }
                foreach (string child in children) pending.Push(child);
                foreach (string file in files) if (IsSupported(file)) yield return file;
            }
        }
    }

    sealed class DspSampleProvider : ISampleProvider
    {
        static readonly float[] Frequencies = { 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 };
        readonly ISampleProvider source;
        readonly WaveStream timing;
        readonly object gate = new object();
        BiQuadFilter[,] filters;
        float[] gains;
        bool enabled, bass, treble;
        public WaveFormat WaveFormat { get { return source.WaveFormat; } }

        public DspSampleProvider(ISampleProvider source, WaveStream timing, float[] gains, bool enabled, bool bass, bool treble)
        { this.source = source; this.timing = timing; Update(gains, enabled, bass, treble); }

        public void Update(float[] values, bool eqEnabled, bool megaBass, bool crystalTreble)
        {
            lock (gate)
            {
                gains = (float[])values.Clone(); enabled = eqEnabled; bass = megaBass; treble = crystalTreble; BuildFilters();
            }
        }

        void BuildFilters()
        {
            int stages = Frequencies.Length + 4, channels = WaveFormat.Channels; filters = new BiQuadFilter[channels, stages];
            for (int channel = 0; channel < channels; channel++)
            {
                for (int band = 0; band < Frequencies.Length; band++)
                {
                    float frequency = Math.Min(Frequencies[band], WaveFormat.SampleRate * 0.45f);
                    if (band == 0) filters[channel, band] = BiQuadFilter.LowShelf(WaveFormat.SampleRate, 46, 0.72f, gains[band] * 1.45f);
                    else if (band == Frequencies.Length - 1) filters[channel, band] = BiQuadFilter.HighShelf(WaveFormat.SampleRate, Math.Min(10000, WaveFormat.SampleRate * 0.40f), 0.72f, gains[band] * 1.55f);
                    else filters[channel, band] = BiQuadFilter.PeakingEQ(WaveFormat.SampleRate, frequency, 1.15f, gains[band]);
                }
                filters[channel, Frequencies.Length] = BiQuadFilter.LowShelf(WaveFormat.SampleRate, 72, 0.72f, 11.5f);
                filters[channel, Frequencies.Length + 1] = BiQuadFilter.PeakingEQ(WaveFormat.SampleRate, 110, 0.95f, 4.8f);
                filters[channel, Frequencies.Length + 2] = BiQuadFilter.HighShelf(WaveFormat.SampleRate, Math.Min(5200, WaveFormat.SampleRate * 0.30f), 0.75f, 6.0f);
                filters[channel, Frequencies.Length + 3] = BiQuadFilter.PeakingEQ(WaveFormat.SampleRate, Math.Min(10500, WaveFormat.SampleRate * 0.42f), 0.85f, 2.8f);
            }
        }

        public void ResetFilters() { lock (gate) BuildFilters(); }

        public int Read(float[] buffer, int offset, int count)
        {
            double startSeconds = 0, totalSeconds = 0;
            try { startSeconds = timing.CurrentTime.TotalSeconds; totalSeconds = timing.TotalTime.TotalSeconds; } catch { }
            int read = source.Read(buffer, offset, count); int channels = WaveFormat.Channels;
            lock (gate)
            {
                for (int index = 0; index < read; index++)
                {
                    int channel = index % channels; float sample = buffer[offset + index];
                    if (enabled) for (int band = 0; band < Frequencies.Length; band++) sample = filters[channel, band].Transform(sample);
                    if (bass) { sample = filters[channel, Frequencies.Length].Transform(sample); sample = filters[channel, Frequencies.Length + 1].Transform(sample); }
                    if (treble) { sample = filters[channel, Frequencies.Length + 2].Transform(sample); sample = filters[channel, Frequencies.Length + 3].Transform(sample); }
                    double sampleSeconds = startSeconds + (double)(index / channels) / Math.Max(1, WaveFormat.SampleRate);
                    float envelope = 1f;
                    if (sampleSeconds < 0.22) envelope = (float)Math.Max(0, Math.Min(1, sampleSeconds / 0.22));
                    if (totalSeconds > 0 && totalSeconds - sampleSeconds < 0.35) envelope = Math.Min(envelope, (float)Math.Max(0, Math.Min(1, (totalSeconds - sampleSeconds) / 0.35)));
                    sample *= envelope;
                    float absolute = Math.Abs(sample);
                    if (absolute > 0.98f) sample = Math.Sign(sample) * (0.98f + (1f - (float)Math.Exp(-(absolute - 0.98f) * 8f)) * 0.02f);
                    buffer[offset + index] = Math.Max(-1, Math.Min(1, sample));
                }
            }
            return read;
        }
    }

    sealed class EqualizerFader : Control
    {
        int value; bool dragging;
        public event EventHandler ValueChanged;
        public int Value { get { return value; } set { int next = Math.Max(-12, Math.Min(12, value)); if (this.value != next) { this.value = next; Invalidate(); } } }

        public EqualizerFader()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.SupportsTransparentBackColor, true);
            DoubleBuffered = true; Cursor = Cursors.Hand; BackColor = Color.Transparent;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics; g.SmoothingMode = SmoothingMode.AntiAlias;
            int top = 9, bottom = Height - 9, center = Width / 2;
            using (Pen rail = new Pen(Color.FromArgb(8, 10, 11), 6)) g.DrawLine(rail, center, top, center, bottom);
            using (Pen edge = new Pen(Color.FromArgb(92, 101, 105), 1))
                for (int tick = 0; tick <= 8; tick++) { int y = top + (bottom - top) * tick / 8; g.DrawLine(edge, 2, y, 8, y); g.DrawLine(edge, Width - 9, y, Width - 3, y); }
            int knobY = bottom - (bottom - top) * (value + 12) / 24;
            Rectangle knob = new Rectangle(5, knobY - 13, Width - 10, 26);
            using (LinearGradientBrush body = new LinearGradientBrush(knob, Color.FromArgb(132, 137, 137), Color.FromArgb(49, 53, 54), LinearGradientMode.Vertical)) g.FillRectangle(body, knob);
            using (Pen outline = new Pen(Color.FromArgb(16, 18, 19), 1)) g.DrawRectangle(outline, knob);
            using (Pen groove = new Pen(Color.FromArgb(35, 38, 39), 1))
                for (int offset = -8; offset <= 8; offset += 4) g.DrawLine(groove, knob.Left + 5, knobY + offset, knob.Right - 5, knobY + offset);
            Color marker = Blend(Color.FromArgb(255, 239, 150), Color.FromArgb(165, 45, 45), (value + 12) / 24f);
            using (Pen line = new Pen(marker, 3)) g.DrawLine(line, knob.Left + 2, knobY, knob.Right - 2, knobY);
        }

        static Color Blend(Color from, Color to, float amount)
        {
            amount = Math.Max(0, Math.Min(1, amount));
            return Color.FromArgb((int)(from.R + (to.R - from.R) * amount), (int)(from.G + (to.G - from.G) * amount), (int)(from.B + (to.B - from.B) * amount));
        }

        void UpdateValue(int y)
        {
            int top = 9, bottom = Height - 9;
            Value = (int)Math.Round(-12 + 24.0 * (bottom - Math.Max(top, Math.Min(bottom, y))) / (bottom - top));
            EventHandler handler = ValueChanged; if (handler != null) handler(this, EventArgs.Empty);
        }

        protected override void OnMouseDown(MouseEventArgs e) { if (e.Button == MouseButtons.Left) { dragging = true; Capture = true; UpdateValue(e.Y); } }
        protected override void OnMouseMove(MouseEventArgs e) { if (dragging) UpdateValue(e.Y); }
        protected override void OnMouseUp(MouseEventArgs e) { dragging = false; Capture = false; }
        protected override void OnMouseWheel(MouseEventArgs e) { Value += e.Delta > 0 ? 1 : -1; EventHandler handler = ValueChanged; if (handler != null) handler(this, EventArgs.Empty); }
        protected override void OnDoubleClick(EventArgs e) { Value = 0; EventHandler handler = ValueChanged; if (handler != null) handler(this, EventArgs.Empty); }
    }

    sealed class TimelineFader : Control
    {
        int value; bool dragging;
        public event EventHandler DragStarted, SeekRequested;
        public int Value { get { return value; } set { int next = Math.Max(0, Math.Min(1000, value)); if (this.value != next) { this.value = next; Invalidate(); } } }

        public TimelineFader()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
            DoubleBuffered = true; Cursor = Cursors.Hand;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics; g.SmoothingMode = SmoothingMode.AntiAlias;
            int left = 11, right = Width - 11, center = Height / 2;
            using (Pen rail = new Pen(Color.FromArgb(8, 10, 11), 6)) g.DrawLine(rail, left, center, right, center);
            using (Pen progress = new Pen(Color.FromArgb(62, 115, 89), 3)) g.DrawLine(progress, left, center, left + (right - left) * value / 1000, center);
            using (Pen tick = new Pen(Color.FromArgb(92, 101, 105), 1))
                for (int index = 0; index <= 10; index++) { int x = left + (right - left) * index / 10; g.DrawLine(tick, x, 2, x, 5); g.DrawLine(tick, x, Height - 6, x, Height - 3); }
            int knobX = left + (right - left) * value / 1000;
            Rectangle knob = new Rectangle(knobX - 10, center - 8, 20, 16);
            using (LinearGradientBrush body = new LinearGradientBrush(knob, Color.FromArgb(132, 137, 137), Color.FromArgb(49, 53, 54), LinearGradientMode.Horizontal)) g.FillRectangle(body, knob);
            using (Pen outline = new Pen(Color.FromArgb(16, 18, 19), 1)) g.DrawRectangle(outline, knob);
            using (Pen groove = new Pen(Color.FromArgb(35, 38, 39), 1))
                for (int offset = -6; offset <= 6; offset += 4) g.DrawLine(groove, knobX + offset, knob.Top + 3, knobX + offset, knob.Bottom - 3);
            Color marker = Blend(Color.FromArgb(255, 239, 150), Color.FromArgb(165, 45, 45), value / 1000f);
            using (Pen line = new Pen(marker, 3)) g.DrawLine(line, knobX, knob.Top + 1, knobX, knob.Bottom - 1);
        }

        static Color Blend(Color from, Color to, float amount)
        {
            amount = Math.Max(0, Math.Min(1, amount));
            return Color.FromArgb((int)(from.R + (to.R - from.R) * amount), (int)(from.G + (to.G - from.G) * amount), (int)(from.B + (to.B - from.B) * amount));
        }

        void UpdateValue(int x) { int left = 11, right = Width - 11; Value = (int)Math.Round(1000.0 * (Math.Max(left, Math.Min(right, x)) - left) / Math.Max(1, right - left)); }
        protected override void OnMouseDown(MouseEventArgs e) { if (e.Button == MouseButtons.Left) { dragging = true; Capture = true; UpdateValue(e.X); EventHandler handler = DragStarted; if (handler != null) handler(this, EventArgs.Empty); } }
        protected override void OnMouseMove(MouseEventArgs e) { if (dragging) UpdateValue(e.X); }
        protected override void OnMouseUp(MouseEventArgs e) { if (!dragging) return; UpdateValue(e.X); dragging = false; Capture = false; EventHandler handler = SeekRequested; if (handler != null) handler(this, EventArgs.Empty); }
    }

    sealed class PlayerForm : Form
    {
        readonly InternalPlayerEngine engine;
        readonly Label title = new Label(), time = new Label(), eqValue = new Label();
        readonly TimelineFader position = new TimelineFader();
        readonly ListBox list = new ListBox();
        readonly Button play = new Button(), previous = new Button(), next = new Button(), stop = new Button(), bass = new Button(), treble = new Button(), eqToggle = new Button(), compactButton = new Button(), viewButton = new Button();
        readonly CheckBox shuffle = new CheckBox();
        readonly EqualizerFader[] bands = new EqualizerFader[10];
        readonly PlayerLevelMeter meter = new PlayerLevelMeter();
        readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
        readonly List<Control> detailControls = new List<Control>();
        bool seeking, refreshing, compactMode;
        public event EventHandler CompactModeChanged;
        public bool CompactMode { get { return compactMode; } }

        public PlayerForm(InternalPlayerEngine engine)
        {
            this.engine = engine; Text = "Audio Personal — Reproductor";AutoScaleMode=AutoScaleMode.None;ClientSize = new Size(790, 640);StartPosition = FormStartPosition.Manual;FormBorderStyle=FormBorderStyle.FixedSingle;MaximizeBox=false;
            Font = new Font("Segoe UI", 9F); Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            title.SetBounds(78, 12, 654, 36); title.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;title.Font = new Font("Segoe UI", 13F, FontStyle.Bold); title.TextAlign = ContentAlignment.MiddleCenter; title.ForeColor = Color.FromArgb(126, 230, 145);
            viewButton.Text="Ver ▾";viewButton.SetBounds(18,15,56,28);viewButton.Click+=delegate{ContextMenuStrip menu=new ContextMenuStrip();menu.Items.Add("Apariencia...",null,delegate{using(AppearanceForm dialog=new AppearanceForm())dialog.ShowDialog(this);});menu.Show(viewButton,new Point(0,viewButton.Height));};
            compactButton.Text="▣";compactButton.SetBounds(742,15,30,28);compactButton.Anchor=AnchorStyles.Top|AnchorStyles.Right;compactButton.Click+=delegate{SetCompact(!compactMode,true);EventHandler handler=CompactModeChanged;if(handler!=null)handler(this,EventArgs.Empty);};
            meter.SetBounds(18, 52, 754, 34);meter.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;
            position.SetBounds(18, 94, 650, 26); position.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            position.DragStarted += delegate { seeking = true; }; position.SeekRequested += delegate { engine.Seek(position.Value / 1000.0); seeking = false; };
            time.SetBounds(676, 94, 96, 27); time.TextAlign = ContentAlignment.MiddleRight; time.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            previous.Text = "|◀"; previous.SetBounds(18, 136, 58, 36); previous.Click += delegate { engine.Previous(); };
            play.Text = "▶"; play.SetBounds(82, 136, 62, 36); play.Click += delegate { engine.TogglePlayPause(); };
            stop.Text = "■"; stop.SetBounds(150, 136, 58, 36); stop.Click += delegate { engine.Stop(); };
            next.Text = "▶|"; next.SetBounds(214, 136, 58, 36); next.Click += delegate { engine.Next(); };
            shuffle.Text = "Aleatorio"; shuffle.SetBounds(286, 143, 88, 26); shuffle.ForeColor = ForeColor; shuffle.CheckedChanged += delegate { if (!refreshing) engine.Shuffle = shuffle.Checked; };
            Button addFiles = NewButton("+ Archivos", 390, 136, 88, delegate { AddFiles(); });
            Button addFolder = NewButton("+ Carpeta", 484, 136, 88, delegate { AddFolder(); });
            Button open = NewButton("Abrir lista", 578, 136, 90, delegate { OpenPlaylist(); });
            Button playlistActions = NewButton("Playlist ▾", 674, 136, 98, delegate(object sender, EventArgs e) { ShowPlaylistMenu((Control)sender); });

            list.SetBounds(18, 190, 754, 188); list.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right; list.BackColor = Color.FromArgb(15, 17, 19); list.ForeColor = Color.WhiteSmoke; list.BorderStyle = BorderStyle.FixedSingle;
            // El refresco periódico selecciona la canción que está sonando. Entre los dos
            // clics podía volver a esa fila y terminar reproduciendo siempre la última.
            // La posición del puntero identifica de forma inequívoca la fila solicitada.
            list.MouseDoubleClick += delegate(object sender, MouseEventArgs e)
            {
                int requestedIndex = list.IndexFromPoint(e.Location);
                if (requestedIndex != ListBox.NoMatches) engine.PlayIndex(requestedIndex);
            };
            list.KeyDown += delegate(object sender, KeyEventArgs e) { if (e.KeyCode == Keys.Delete && list.SelectedIndex >= 0) engine.RemoveAt(list.SelectedIndex); };

            Label equalizer = NewLabel("ECUALIZADOR", 18, 400, 135, 24); equalizer.Font = new Font("Segoe UI", 9F, FontStyle.Bold);
            eqToggle = NewButton("EQ ON", 158, 394, 72, delegate { engine.EqualizerEnabled = !engine.EqualizerEnabled; });
            bass = NewButton("MEGABASS", 238, 394, 104, delegate { engine.MegaBass = !engine.MegaBass; eqValue.Text = engine.MegaBass ? "MEGABASS activado: refuerzo profundo de graves" : "MEGABASS desactivado"; });
            treble = NewButton("CRYSTAL TREBLE", 350, 394, 132, delegate { engine.CrystalTreble = !engine.CrystalTreble; eqValue.Text = engine.CrystalTreble ? "CRYSTAL TREBLE activado: brillo y presencia" : "CRYSTAL TREBLE desactivado"; });
            Button reset = NewButton("RESET", 490, 394, 72, delegate { engine.ResetEqualizer(); RefreshState(); });
            Button remove = NewButton("Quitar", 580, 394, 88, delegate { if (list.SelectedIndex >= 0) engine.RemoveAt(list.SelectedIndex); });
            Button clear = NewButton("Limpiar lista", 674, 394, 98, delegate { if (MessageBox.Show("¿Vaciar la playlist?", "Audio Personal", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes) engine.ClearPlaylist(); });
            eqValue.SetBounds(18, 438, 754, 22); eqValue.TextAlign = ContentAlignment.MiddleCenter; eqValue.Text = "Mueva una banda para ajustar entre -12 y +12 dB";

            string[] names = { "31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K" };
            int startX = 50, spacing = 69;
            for (int index = 0; index < bands.Length; index++)
            {
                int captured = index; EqualizerFader band = new EqualizerFader(); bands[index] = band;
                band.Value = 0; band.SetBounds(startX + index * spacing, 462, 45, 125);
                band.ValueChanged += delegate { if (!refreshing) { engine.SetEqGain(captured, band.Value); eqValue.Text = names[captured] + " Hz: " + (band.Value >= 0 ? "+" : "") + band.Value + " dB"; } };
                Controls.Add(band);detailControls.Add(band);Label name = NewLabel(names[index], startX - 2 + index * spacing, 587, 48, 22); name.TextAlign = ContentAlignment.MiddleCenter;detailControls.Add(name);
            }
            Label hint = NewLabel("Doble clic para reproducir. También puede arrastrar canciones desde el Explorador.", 18, 614, 754, 20); hint.TextAlign = ContentAlignment.MiddleCenter; hint.ForeColor = Color.Silver;

            Controls.AddRange(new Control[] { title,viewButton,compactButton,meter, position, time, previous, play, stop, next, shuffle, addFiles, addFolder, open, playlistActions, list, equalizer, eqToggle, bass, treble, reset, remove, clear, eqValue, hint });
            detailControls.AddRange(new Control[]{addFiles,addFolder,open,playlistActions,list,equalizer,eqToggle,bass,treble,reset,remove,clear,eqValue,hint});
            foreach (Control control in Controls) if (control is Button) StyleButton((Button)control);
            AppearanceManager.Changed+=AppearanceChanged;ApplyAppearance();
            engine.StateChanged += EngineChanged; engine.PlaylistChanged += EnginePlaylistChanged; engine.Error += EngineError;
            timer.Interval = 180; timer.Tick += delegate { RefreshState(); }; timer.Start(); RefreshPlaylist(); RefreshState();
            VisibleChanged += delegate { timer.Enabled = Visible;if(Visible){AppearanceManager.ApplyRounded(this);RefreshPlaylist();RefreshState();} };
            AllowDrop=true;DragEnter+=PlayerDragEnter;DragDrop+=PlayerDragDrop;
            FormClosing += delegate(object sender, FormClosingEventArgs e) { if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); } };
            int savedCompact=0;try{savedCompact=Convert.ToInt32(Registry.GetValue(@"HKEY_CURRENT_USER\Software\AudioPersonal","PlayerCompact",0));}catch{}SetCompact(savedCompact==1,false);
        }

        Button NewButton(string text, int x, int y, int width, EventHandler click) { Button button = new Button(); button.Text = text; button.SetBounds(x, y, width, 36); button.Click += click; return button; }
        Label NewLabel(string text, int x, int y, int width, int height) { Label label = new Label(); label.Text = text; label.SetBounds(x, y, width, height); Controls.Add(label); return label; }
        void StyleButton(Button button) { AppearanceManager.StyleButton(button,AppearanceManager.Palette); }
        void AppearanceChanged(object sender,EventArgs e){ApplyAppearance();}
        void ApplyAppearance()
        {
            AppearancePalette palette=AppearanceManager.Palette;BackColor=palette.Background;ForeColor=palette.Text;title.ForeColor=palette.Accent;list.BackColor=palette.Dark?Darken(palette.Background,18):Darken(palette.Background,7);list.ForeColor=palette.Text;AppearanceManager.ApplyRoundedControl(list,12);
            foreach(Control control in Controls){Button button=control as Button;if(button!=null)AppearanceManager.StyleButton(button,palette);else if(control is Label||control is CheckBox){control.BackColor=palette.Background;control.ForeColor=palette.Text;}}
            Invalidate(true);
        }
        static Color Darken(Color color,int amount){return Color.FromArgb(Math.Max(0,color.R-amount),Math.Max(0,color.G-amount),Math.Max(0,color.B-amount));}
        public void ApplyCompact(bool compact,bool save){SetCompact(compact,save);}
        void SetCompact(bool compact,bool save){compactMode=compact;foreach(Control control in detailControls)control.Visible=!compact;MinimumSize=Size.Empty;MaximumSize=Size.Empty;ClientSize=compact?new Size(560,185):new Size(790,640);compactButton.Text=compact?"□":"▣";MinimumSize=MaximumSize=Size;if(save)try{using(RegistryKey key=Registry.CurrentUser.CreateSubKey(@"Software\AudioPersonal"))key.SetValue("PlayerCompact",compact?1:0);}catch{}Rectangle area=Screen.FromControl(this).WorkingArea;Location=new Point(Math.Max(area.Left,Math.Min(Left,area.Right-Width)),Math.Max(area.Top,Math.Min(Top,area.Bottom-Height)));}

        void RefreshState()
        {
            if (IsDisposed) return; refreshing = true;
            title.Text = engine.CurrentTitle; play.Text = engine.IsPlaying ? "❚❚" : "▶"; shuffle.Checked = engine.Shuffle;
            Color inactive=AppearanceManager.Palette.Surface;
            eqToggle.BackColor = engine.EqualizerEnabled ? Color.FromArgb(42, 112, 65) : inactive;
            bass.BackColor = engine.MegaBass ? Color.FromArgb(38, 116, 162) : inactive; bass.Text = engine.MegaBass ? "MEGABASS ●" : "MEGABASS";
            treble.BackColor = engine.CrystalTreble ? Color.FromArgb(155, 98, 30) : inactive; treble.Text = engine.CrystalTreble ? "CRYSTAL TREBLE ●" : "CRYSTAL TREBLE";
            for (int index = 0; index < bands.Length; index++) bands[index].Value = (int)Math.Round(engine.GetEqGain(index));
            TimeSpan elapsed = engine.Position, duration = engine.Duration; time.Text = FormatTime(elapsed) + " / " + FormatTime(duration);
            if (!seeking) position.Value = duration.TotalMilliseconds <= 0 ? 0 : Math.Max(0, Math.Min(1000, (int)(elapsed.TotalMilliseconds * 1000 / duration.TotalMilliseconds)));
            // La fila seleccionada por el usuario no debe ser reemplazada por la
            // canción que está sonando. Así el clic simple queda disponible para
            // «Quitar», incluso mientras continúa la reproducción.
            float left, right; engine.GetLevels(out left, out right); meter.SetLevels(left, right); refreshing = false;
        }

        void RefreshPlaylist()
        {
            if (InvokeRequired) { BeginInvoke((MethodInvoker)RefreshPlaylist); return; }
            int selected = list.SelectedIndex; list.BeginUpdate(); list.Items.Clear(); foreach (TrackInfo track in engine.PlaylistSnapshot) list.Items.Add(track.DisplayName); list.EndUpdate();
            if (selected >= 0 && selected < list.Items.Count) list.SelectedIndex = selected;
        }

        void AddFiles()
        {
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Multiselect = true; dialog.Title = "Agregar canciones"; dialog.Filter = "Audio|*.mp3;*.flac;*.wav;*.aiff;*.m4a;*.ogg;*.mp4;*.wma;*.aac;*.opus|Todos|*.*";dialog.InitialDirectory=MusicRoot();dialog.RestoreDirectory=true;
                if (dialog.ShowDialog(this) == DialogResult.OK) engine.AddFiles(dialog.FileNames);
            }
        }

        void AddFolder()
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                string root=MusicRoot();if(Directory.Exists(root))dialog.SelectedPath=root;
                if(dialog.ShowDialog(this)==DialogResult.OK){string folder = dialog.SelectedPath; ThreadPool.QueueUserWorkItem(delegate { engine.AddFiles(MusicFiles.Enumerate(folder)); });}
            }
        }

        string MusicRoot(){try{string root=AppSettings.Load().MusicFolder;if(Directory.Exists(root))return root;}catch{}return Environment.GetFolderPath(Environment.SpecialFolder.MyMusic);}
        void OpenPlaylist() { using (OpenFileDialog dialog = new OpenFileDialog()) { dialog.Filter = "Playlist M3U|*.m3u;*.m3u8|Todos|*.*";dialog.InitialDirectory=MusicRoot();dialog.RestoreDirectory=true;if (dialog.ShowDialog(this) == DialogResult.OK) engine.LoadPlaylist(dialog.FileName, true); } }
        void SavePlaylist() { using (SaveFileDialog dialog = new SaveFileDialog()) { dialog.Filter = "Playlist M3U8|*.m3u8"; dialog.FileName = "Mi playlist.m3u8";dialog.InitialDirectory=MusicRoot();dialog.RestoreDirectory=true;if (dialog.ShowDialog(this) == DialogResult.OK) engine.SavePlaylist(dialog.FileName); } }
        void ShowPlaylistMenu(Control anchor)
        {
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add("Agregar canción seleccionada a una lista...", null, delegate { AddSelectedToPlaylist(); });
            menu.Items.Add("Abrir una lista...", null, delegate { OpenPlaylist(); });
            menu.Items.Add("Guardar playlist actual...", null, delegate { SavePlaylist(); });
            menu.Closed += delegate { menu.Dispose(); };
            menu.Show(anchor, new Point(0, anchor.Height));
        }

        void AddSelectedToPlaylist()
        {
            int selected = list.SelectedIndex; IList<TrackInfo> snapshot = engine.PlaylistSnapshot;
            if (selected < 0 || selected >= snapshot.Count)
            {
                MessageBox.Show(this, "Seleccione primero una canción de la playlist.", "Audio Personal", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            TrackInfo track = snapshot[selected];
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "Agregar a una playlist existente"; dialog.Filter = "Playlist M3U|*.m3u;*.m3u8|Todos|*.*"; dialog.InitialDirectory = MusicRoot(); dialog.RestoreDirectory = true;
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                try
                {
                    bool added = engine.AppendTrackToPlaylist(dialog.FileName, track);
                    MessageBox.Show(this, added ? "La canción se agregó a la playlist seleccionada." : "La canción ya estaba en esa playlist.", "Audio Personal", MessageBoxButtons.OK, MessageBoxIcon.Information);
                }
                catch (Exception ex) { MessageBox.Show(this, "No se pudo modificar la playlist: " + ex.Message, "Audio Personal", MessageBoxButtons.OK, MessageBoxIcon.Warning); }
            }
        }
        void PlayerDragEnter(object sender,DragEventArgs e){e.Effect=e.Data!=null&&e.Data.GetDataPresent(DataFormats.FileDrop)?DragDropEffects.Copy:DragDropEffects.None;}
        void PlayerDragDrop(object sender,DragEventArgs e){if(e.Data==null||!e.Data.GetDataPresent(DataFormats.FileDrop))return;string[] paths=e.Data.GetData(DataFormats.FileDrop) as string[];if(paths==null||paths.Length==0)return;ThreadPool.QueueUserWorkItem(delegate{List<string> files=new List<string>();foreach(string path in paths){if(Directory.Exists(path))files.AddRange(MusicFiles.Enumerate(path));else if(File.Exists(path)&&MusicFiles.IsSupported(path))files.Add(path);}engine.AddFiles(files);});}
        static string FormatTime(TimeSpan value) { return value.TotalHours >= 1 ? value.ToString(@"h\:mm\:ss") : value.ToString(@"m\:ss"); }
        void EngineChanged(object sender, EventArgs e) { if (IsHandleCreated) try { BeginInvoke((MethodInvoker)RefreshState); } catch { } }
        void EnginePlaylistChanged(object sender, EventArgs e) { if (IsHandleCreated) try { BeginInvoke((MethodInvoker)RefreshPlaylist); } catch { } }
        void EngineError(object sender, string message) { if (IsHandleCreated) try { BeginInvoke((MethodInvoker)delegate { MessageBox.Show(this, message, "Audio Personal", MessageBoxButtons.OK, MessageBoxIcon.Warning); }); } catch { } }
        protected override void Dispose(bool disposing) { if (disposing) { timer.Dispose(); engine.StateChanged -= EngineChanged; engine.PlaylistChanged -= EnginePlaylistChanged; engine.Error -= EngineError;AppearanceManager.Changed-=AppearanceChanged; } base.Dispose(disposing); }
    }

    sealed class PlayerLevelMeter : Control
    {
        float left, right;
        public PlayerLevelMeter() { DoubleBuffered = true; }
        public void SetLevels(float l, float r) { left = Math.Max(l, left * 0.78f); right = Math.Max(r, right * 0.78f); Invalidate(); }
        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.Clear(Color.FromArgb(11, 14, 13)); Draw(e.Graphics, 4, left); Draw(e.Graphics, 19, right);
        }
        void Draw(Graphics graphics, int y, float level)
        {
            int segments = 48, gap = 2, width = (Width - 8) / segments;
            for (int index = 0; index < segments; index++)
            {
                float point = (index + 1f) / segments; Color active = point > .9f ? Color.FromArgb(240, 55, 50) : point > .72f ? Color.FromArgb(245, 165, 35) : Color.FromArgb(55, 210, 92);
                using (Brush brush = new SolidBrush(point <= level ? active : Color.FromArgb(22, 48, 29))) graphics.FillRectangle(brush, 4 + index * width, y, Math.Max(1, width - gap), 10);
            }
        }
    }
}
