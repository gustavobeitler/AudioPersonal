using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("Audio Personal Beta 4.12.4")]
[assembly: AssemblyProduct("Audio Personal")]
[assembly: AssemblyDescription("Reproductor local y control de volumen por voz")]
[assembly: AssemblyVersion("4.12.4.0")]
[assembly: AssemblyFileVersion("4.12.4.0")]

namespace AudioPersonal
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
            Application.ThreadException += delegate(object sender, ThreadExceptionEventArgs e) { LogUnexpected(e.Exception); };
            AppDomain.CurrentDomain.UnhandledException += delegate(object sender, UnhandledExceptionEventArgs e) { LogUnexpected(e.ExceptionObject as Exception); };
            bool firstInstance;
            using(Mutex instance=new Mutex(true,@"Local\AudioPersonal-Principal",out firstInstance))
            {
                if(!firstInstance){MessageBox.Show("Audio Personal ya está abierto. Revise el icono junto al reloj.","Audio Personal",MessageBoxButtons.OK,MessageBoxIcon.Information);return;}
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Startup.Enable();
                Application.Run(new VolumeForm());
            }
        }

        static void LogUnexpected(Exception error)
        {
            try
            {
                string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AudioPersonal");
                Directory.CreateDirectory(folder);
                File.AppendAllText(Path.Combine(folder, "errores.log"), DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + Environment.NewLine + (error == null ? "Error desconocido" : error.ToString()) + Environment.NewLine + Environment.NewLine, Encoding.UTF8);
            }
            catch { }
        }
    }

    internal sealed class AppearancePalette
    {
        public Color Background, Surface, Text, Border, Accent;
        public bool Dark;
    }

    internal static class AppearanceManager
    {
        const string RegistryPath=@"Software\AudioPersonal\Appearance";
        static int hue=205,intensity=58,background=22,contrast=62;
        static bool followsWindows=true;
        public static event EventHandler Changed;
        static AppearanceManager(){Load();}
        public static int Hue{get{return hue;}}public static int Intensity{get{return intensity;}}public static int Background{get{return background;}}public static int Contrast{get{return contrast;}}public static bool FollowsWindows{get{return followsWindows;}}
        public static AppearancePalette Palette{get{return CreatePalette(hue,intensity,background,contrast);}}
        public static void SetPreview(int color,int strength,int backdrop,int difference)
        {
            hue=Math.Max(0,Math.Min(359,color));intensity=Math.Max(0,Math.Min(100,strength));background=Math.Max(4,Math.Min(96,backdrop));contrast=Math.Max(0,Math.Min(100,difference));
            EventHandler handler=Changed;if(handler!=null)handler(null,EventArgs.Empty);
        }
        public static void Save()
        {
            followsWindows=false;try{using(RegistryKey key=Registry.CurrentUser.CreateSubKey(RegistryPath)){key.SetValue("Color",hue);key.SetValue("Intensidad",intensity);key.SetValue("Fondo",background);key.SetValue("Contraste",contrast);key.SetValue("Personalizada",1);}}catch{}
        }
        public static void ResetToWindows(){followsWindows=true;SetPreview(205,58,WindowsUsesDarkMode()?22:91,62);try{using(RegistryKey key=Registry.CurrentUser.CreateSubKey(RegistryPath))key.SetValue("Personalizada",0);}catch{}}
        public static void RefreshWindowsTheme(){if(followsWindows)SetPreview(hue,intensity,WindowsUsesDarkMode()?22:91,contrast);}
        static bool WindowsUsesDarkMode(){try{return Convert.ToInt32(Registry.GetValue(@"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize","AppsUseLightTheme",0))==0;}catch{return true;}}
        static void Load()
        {
            try{followsWindows=Convert.ToInt32(Registry.GetValue(@"HKEY_CURRENT_USER\"+RegistryPath,"Personalizada",0))==0;hue=Convert.ToInt32(Registry.GetValue(@"HKEY_CURRENT_USER\"+RegistryPath,"Color",205));intensity=Convert.ToInt32(Registry.GetValue(@"HKEY_CURRENT_USER\"+RegistryPath,"Intensidad",58));background=followsWindows?(WindowsUsesDarkMode()?22:91):Convert.ToInt32(Registry.GetValue(@"HKEY_CURRENT_USER\"+RegistryPath,"Fondo",22));contrast=Convert.ToInt32(Registry.GetValue(@"HKEY_CURRENT_USER\"+RegistryPath,"Contraste",62));}catch{}
        }
        public static AppearancePalette CreatePalette(int color,int strength,int backdrop,int difference)
        {
            double light=backdrop/100.0,saturation=strength/100.0;Color bg=FromHsl(color,saturation*.22,light);
            double direction=light<.52?1:-1;double surfaceLight=Math.Max(.02,Math.Min(.98,light+direction*(.035+.09*difference/100.0)));
            double textLight=light<.52?.80+.18*difference/100.0:.20-.16*difference/100.0;
            return new AppearancePalette{Background=bg,Surface=FromHsl(color,saturation*.27,surfaceLight),Text=FromHsl(color,saturation*.08,Math.Max(.04,Math.Min(.97,textLight))),Border=FromHsl(color,saturation*.35,Math.Max(.08,Math.Min(.92,surfaceLight+direction*.16))),Accent=FromHsl(color,Math.Max(.35,saturation),light<.52?.57:.38),Dark=light<.52};
        }
        static Color FromHsl(double h,double s,double l)
        {
            h=((h%360)+360)%360/360.0;s=Math.Max(0,Math.Min(1,s));l=Math.Max(0,Math.Min(1,l));double r=l,g=l,b=l;
            if(s>0){double q=l<.5?l*(1+s):l+s-l*s,p=2*l-q;r=HuePart(p,q,h+1.0/3);g=HuePart(p,q,h);b=HuePart(p,q,h-1.0/3);}
            return Color.FromArgb((int)Math.Round(r*255),(int)Math.Round(g*255),(int)Math.Round(b*255));
        }
        static double HuePart(double p,double q,double t){if(t<0)t+=1;if(t>1)t-=1;if(t<1.0/6)return p+(q-p)*6*t;if(t<.5)return q;if(t<2.0/3)return p+(q-p)*(2.0/3-t)*6;return p;}
        public static void ApplyRounded(Form form)
        {
            if(form==null||!form.IsHandleCreated)return;
            if(form.FormBorderStyle==FormBorderStyle.None)
            {
                using(GraphicsPath path=new GraphicsPath()){int radius=18;Rectangle bounds=new Rectangle(0,0,form.Width,form.Height);path.AddArc(bounds.Left,bounds.Top,radius,radius,180,90);path.AddArc(bounds.Right-radius,bounds.Top,radius,radius,270,90);path.AddArc(bounds.Right-radius,bounds.Bottom-radius,radius,radius,0,90);path.AddArc(bounds.Left,bounds.Bottom-radius,radius,radius,90,90);path.CloseFigure();form.Region=new Region(path);}
            }
            try{int preference=2;Native.DwmSetWindowAttribute(form.Handle,33,ref preference,sizeof(int));}catch{}
        }
        public static void StyleButton(Button button,AppearancePalette palette)
        {
            button.FlatStyle=FlatStyle.Flat;button.FlatAppearance.BorderColor=palette.Border;button.FlatAppearance.BorderSize=1;button.BackColor=palette.Surface;button.ForeColor=palette.Text;ApplyRoundedControl(button,10);
        }
        public static void ApplyRoundedControl(Control control,int radius)
        {
            if(control==null||control.Width<2||control.Height<2)return;using(GraphicsPath path=new GraphicsPath()){Rectangle bounds=new Rectangle(0,0,control.Width,control.Height);int diameter=Math.Max(2,Math.Min(radius*2,Math.Min(bounds.Width,bounds.Height)));path.AddArc(bounds.Left,bounds.Top,diameter,diameter,180,90);path.AddArc(bounds.Right-diameter,bounds.Top,diameter,diameter,270,90);path.AddArc(bounds.Right-diameter,bounds.Bottom-diameter,diameter,diameter,0,90);path.AddArc(bounds.Left,bounds.Bottom-diameter,diameter,diameter,90,90);path.CloseFigure();Region old=control.Region;control.Region=new Region(path);if(old!=null)old.Dispose();}
        }
    }

    internal sealed class AppearanceForm:Form
    {
        readonly TrackBar color=new TrackBar(),intensity=new TrackBar(),background=new TrackBar(),contrast=new TrackBar();
        readonly Panel preview=new Panel();readonly Button save=new Button(),reset=new Button(),cancel=new Button();
        readonly int oldColor,oldIntensity,oldBackground,oldContrast;
        public AppearanceForm()
        {
            oldColor=AppearanceManager.Hue;oldIntensity=AppearanceManager.Intensity;oldBackground=AppearanceManager.Background;oldContrast=AppearanceManager.Contrast;
            Text="Apariencia";ClientSize=new Size(430,330);FormBorderStyle=FormBorderStyle.FixedDialog;MaximizeBox=MinimizeBox=false;ShowInTaskbar=false;StartPosition=FormStartPosition.CenterParent;Font=new Font("Segoe UI",9F);
            AddSlider("Color",color,18,oldColor,0,359);AddSlider("Intensidad",intensity,76,oldIntensity,0,100);AddSlider("Fondo",background,134,oldBackground,4,96);AddSlider("Contraste",contrast,192,oldContrast,0,100);
            preview.SetBounds(22,252,130,48);Controls.Add(preview);
            save.Text="Guardar";save.SetBounds(168,260,78,32);save.Click+=delegate{AppearanceManager.Save();DialogResult=DialogResult.OK;Close();};
            reset.Text="Usar Windows";reset.SetBounds(252,260,88,32);reset.Click+=delegate{AppearanceManager.ResetToWindows();color.Value=AppearanceManager.Hue;intensity.Value=AppearanceManager.Intensity;background.Value=AppearanceManager.Background;contrast.Value=AppearanceManager.Contrast;Preview();};
            cancel.Text="Cancelar";cancel.SetBounds(346,260,72,32);cancel.Click+=delegate{AppearanceManager.SetPreview(oldColor,oldIntensity,oldBackground,oldContrast);DialogResult=DialogResult.Cancel;Close();};
            Controls.AddRange(new Control[]{save,reset,cancel});FormClosing+=delegate(object sender,FormClosingEventArgs e){if(DialogResult!=DialogResult.OK)AppearanceManager.SetPreview(oldColor,oldIntensity,oldBackground,oldContrast);};
            Shown+=delegate{AppearanceManager.ApplyRounded(this);Preview();};
        }
        void AddSlider(string text,TrackBar slider,int y,int value,int minimum,int maximum)
        {
            Label label=new Label();label.Text=text;label.SetBounds(20,y,90,24);slider.SetBounds(108,y-5,304,45);slider.Minimum=minimum;slider.Maximum=maximum;slider.TickFrequency=Math.Max(1,(maximum-minimum)/10);slider.Value=Math.Max(minimum,Math.Min(maximum,value));slider.ValueChanged+=delegate{Preview();};Controls.Add(label);Controls.Add(slider);
        }
        void Preview()
        {
            AppearanceManager.SetPreview(color.Value,intensity.Value,background.Value,contrast.Value);AppearancePalette p=AppearanceManager.Palette;BackColor=p.Background;ForeColor=p.Text;preview.BackColor=p.Surface;
            foreach(Control control in Controls){if(control is Label){control.BackColor=p.Background;control.ForeColor=p.Text;}Button button=control as Button;if(button!=null)AppearanceManager.StyleButton(button,p);}
            preview.Invalidate();
        }
    }

    internal sealed class VolumeForm : Form
    {
        readonly ConsoleFader fader=new ConsoleFader();readonly StereoMeter meter=new StereoMeter();readonly BalanceControl balance=new BalanceControl();
        readonly Label percent=new Label(),balanceText=new Label(),voiceState=new Label();readonly Button mute=new Button(),minimize=new Button(),music=new Button(),resize=new Button(),view=new Button();
        readonly NotifyIcon tray=new NotifyIcon();readonly System.Windows.Forms.Timer refresh=new System.Windows.Forms.Timer(),duckTimer=new System.Windows.Forms.Timer();readonly InternalPlayerEngine playerEngine=new InternalPlayerEngine();AudioDevice audio;MainForm musicForm;PlayerForm playerForm;bool internalChange,forceExit,voiceDucked,compactPanel,synchronizingCompact,movingPair;float volumeBeforeVoice;
        public VolumeForm()
        {
            Text="Audio Personal";FormBorderStyle=FormBorderStyle.None;ClientSize=new Size(174,490);MinimumSize=MaximumSize=Size;StartPosition=FormStartPosition.Manual;TopMost=true;ShowInTaskbar=false;
            Rectangle area=Screen.PrimaryScreen.WorkingArea;Location=new Point(area.Right-Width,area.Top);
            percent.SetBounds(7,10,68,28);percent.TextAlign=ContentAlignment.MiddleCenter;percent.Font=new Font("Segoe UI",11F,FontStyle.Bold);
            resize.SetBounds(52,8,26,25);resize.FlatStyle=FlatStyle.Flat;resize.Text="▣";resize.Font=new Font("Segoe UI Symbol",10F,FontStyle.Bold);resize.Click+=delegate{SetCombinedCompact(!compactPanel,true);};
            view.SetBounds(81,8,45,25);view.FlatStyle=FlatStyle.Flat;view.Text="Ver ▾";view.Font=new Font("Segoe UI",8F);view.Click+=delegate{ShowViewMenu(view);};
            music.SetBounds(129,8,27,25);music.FlatStyle=FlatStyle.Flat;music.Text="▶";music.Font=new Font("Segoe UI Symbol",10F,FontStyle.Bold);music.AccessibleName="Abrir reproductor";music.Click+=delegate{OpenPlayer();};
            minimize.SetBounds(158,8,16,25);minimize.FlatStyle=FlatStyle.Flat;minimize.Text="─";minimize.Click+=delegate{Hide();};
            fader.SetBounds(10,47,88,245);fader.ValueChanged+=delegate{if(!internalChange&&audio!=null)audio.Volume=fader.Value/100f;percent.Text=fader.Value+"%";};meter.SetBounds(104,47,60,245);
            balanceText.SetBounds(10,297,154,20);balanceText.Text="BALANCE   C";balanceText.TextAlign=ContentAlignment.MiddleCenter;balanceText.Font=new Font("Segoe UI",8F,FontStyle.Bold);
            balance.SetBounds(17,319,140,32);balance.ValueChanged+=delegate{if(!internalChange&&audio!=null)audio.SetBalance(balance.Value);balanceText.Text=BalanceLabel(balance.Value);};
            mute.SetBounds(44,361,86,34);mute.FlatStyle=FlatStyle.Flat;mute.Font=new Font("Segoe UI",9F,FontStyle.Bold);mute.Text="MUTE";mute.Click+=delegate{if(audio!=null)audio.Muted=!audio.Muted;RefreshAudio();};
            voiceState.SetBounds(9,400,156,80);voiceState.BorderStyle=BorderStyle.FixedSingle;voiceState.TextAlign=ContentAlignment.MiddleCenter;voiceState.Font=new Font("Segoe UI",8F,FontStyle.Bold);voiceState.Text="♫ Preparando voz...";voiceState.Click+=delegate{OpenMusic();};
            Controls.AddRange(new Control[]{percent,resize,view,music,minimize,fader,meter,balanceText,balance,mute,voiceState});MouseDown+=DragWindow;percent.MouseDown+=DragWindow;balanceText.MouseDown+=DragWindow;
            audio=AudioDevice.TryCreate();if(audio!=null){audio.SetBalance(0);audio.Volume=0.30f;}refresh.Interval=35;refresh.Tick+=delegate{RefreshAudio();};refresh.Start();duckTimer.Interval=12000;duckTimer.Tick+=delegate{RestoreAfterVoice();};
            ContextMenuStrip menu=new ContextMenuStrip();menu.Items.Add("Mostrar reproductor y volumen",null,delegate{ShowPanel();});menu.Items.Add("Configurar voz y catálogo",null,delegate{OpenMusic();});menu.Items.Add("Apariencia...",null,delegate{OpenAppearance();});menu.Items.Add("Salir",null,delegate{forceExit=true;tray.Visible=false;Application.Exit();});
            tray.Icon=Icon.ExtractAssociatedIcon(Application.ExecutablePath)??SystemIcons.Application;tray.Text="Audio Personal";tray.ContextMenuStrip=menu;tray.Visible=true;tray.DoubleClick+=delegate{ShowPanel();};
            ApplyTheme();AppearanceManager.Changed+=AppearanceChanged;SetPanelCompact(SafeInt(Registry.GetValue(@"HKEY_CURRENT_USER\Software\AudioPersonal","PanelCompact",0),0)==1,false);MovePanelToTopRight();SystemEvents.UserPreferenceChanged+=ThemeChanged;LocationChanged+=delegate{SaveLocation();if(playerForm!=null&&playerForm.Visible&&!movingPair)MovePairFromPanel();};
            FormClosing+=delegate(object sender,FormClosingEventArgs e){if(!forceExit&&e.CloseReason==CloseReason.UserClosing){e.Cancel=true;Hide();}};
            Shown+=delegate{AppearanceManager.ApplyRounded(this);musicForm=new MainForm(UpdateVoiceState,DuckForVoice,RestoreAfterVoice,SetVolumePercent,AdjustVolumePercent,playerEngine,OpenPlayer,ClosePlayer);musicForm.Initialize();if(!musicForm.HasMusicFolder)OpenMusic();};
        }
        static int SafeInt(object value,int fallback){try{return Convert.ToInt32(value);}catch{return fallback;}}
        static string BalanceLabel(int value){if(Math.Abs(value)<=2)return "BALANCE   C";return value<0?"BALANCE   L "+Math.Abs(value):"BALANCE   R "+value;}
        void ShowPanel(){ShowPanelOnly();OpenPlayerOnly();}
        void ShowPanelOnly(){Show();WindowState=FormWindowState.Normal;Activate();}
        void OpenMusic(){if(musicForm==null||musicForm.IsDisposed)musicForm=new MainForm(UpdateVoiceState,DuckForVoice,RestoreAfterVoice,SetVolumePercent,AdjustVolumePercent,playerEngine,OpenPlayer,ClosePlayer);musicForm.Initialize();musicForm.Show();musicForm.WindowState=FormWindowState.Normal;musicForm.Activate();}
        void OpenAppearance(){using(AppearanceForm dialog=new AppearanceForm())dialog.ShowDialog(this);}
        void ShowViewMenu(Control owner){ContextMenuStrip menu=new ContextMenuStrip();menu.RenderMode=ToolStripRenderMode.System;menu.Items.Add("Apariencia...",null,delegate{OpenAppearance();});menu.Show(owner,new Point(0,owner.Height));}
        void OpenPlayer(){ShowPanelOnly();OpenPlayerOnly();}
        void ClosePlayer(){playerEngine.Stop();if(playerForm!=null&&!playerForm.IsDisposed)playerForm.Hide();}
        void OpenPlayerOnly(){bool wasVisible=playerForm!=null&&!playerForm.IsDisposed&&playerForm.Visible;if(playerForm==null||playerForm.IsDisposed){playerForm=new PlayerForm(playerEngine);playerForm.CompactModeChanged+=delegate{if(!synchronizingCompact)SetCombinedCompact(playerForm.CompactMode,true);};playerForm.ApplyCompact(compactPanel,true);playerForm.LocationChanged+=delegate{if(playerForm.Visible&&!movingPair)MovePairFromPlayer();};playerForm.SizeChanged+=delegate{if(playerForm.Visible&&!movingPair)MovePairFromPlayer();};}if(!wasVisible)playerForm.Location=new Point(Left-playerForm.Width,Top);playerForm.Show();playerForm.WindowState=FormWindowState.Normal;if(wasVisible)MovePairFromPlayer();else MovePairFromPanel();playerForm.Activate();}
        void MovePanelToTopRight(){Rectangle area=Screen.PrimaryScreen.WorkingArea;Location=new Point(area.Right-Width,area.Top);}
        void MovePairFromPlayer(){if(movingPair||playerForm==null||playerForm.IsDisposed||!playerForm.Visible)return;movingPair=true;try{SetPanelHeight(playerForm.Height);Rectangle area=Screen.FromControl(playerForm).WorkingArea;int totalWidth=playerForm.Width+Width;int x=Math.Max(area.Left,Math.Min(playerForm.Left,area.Right-totalWidth));int y=Math.Max(area.Top,Math.Min(playerForm.Top,area.Bottom-playerForm.Height));playerForm.Location=new Point(x,y);Location=new Point(playerForm.Right,y);}finally{movingPair=false;}}
        void MovePairFromPanel(){if(movingPair||playerForm==null||playerForm.IsDisposed||!playerForm.Visible)return;movingPair=true;try{SetPanelHeight(playerForm.Height);Rectangle area=Screen.FromControl(this).WorkingArea;int totalWidth=playerForm.Width+Width;int x=Math.Max(area.Left,Math.Min(Left-playerForm.Width,area.Right-totalWidth));int y=Math.Max(area.Top,Math.Min(Top,area.Bottom-playerForm.Height));playerForm.Location=new Point(x,y);Location=new Point(playerForm.Right,y);}finally{movingPair=false;}}
        void UpdateVoiceState(string message){if(IsDisposed||!IsHandleCreated)return;try{BeginInvoke((MethodInvoker)delegate{string s=message??"";if(s.StartsWith("Escuchando"))voiceState.Text="♫ Escuchando\nDiga: computadora";else if(s.StartsWith("Reproduciendo"))voiceState.Text="♫ REPRODUCIENDO\n"+Shorten(s.Substring(13),72);else if(s.StartsWith("Buscando"))voiceState.Text="⌕ BUSCANDO\n"+Shorten(s.Substring(8),55);else if(s.StartsWith("Error")||s.StartsWith("No se pudo"))voiceState.Text="⚠\n"+Shorten(s,65);else voiceState.Text="♫\n"+Shorten(s,65);});}catch{}}
        void DuckForVoice(){if(audio==null)return;if(!voiceDucked){volumeBeforeVoice=audio.Volume;voiceDucked=true;}duckTimer.Stop();duckTimer.Start();if(audio.Volume>0.10f)audio.Volume=0.10f;}
        void RestoreAfterVoice(){duckTimer.Stop();if(!voiceDucked||audio==null)return;audio.Volume=volumeBeforeVoice;voiceDucked=false;}
        void SetVolumePercent(int value){if(audio==null)return;duckTimer.Stop();voiceDucked=false;audio.Volume=Math.Max(0,Math.Min(100,value))/100f;RefreshAudio();}
        void AdjustVolumePercent(int delta){if(audio==null)return;float basis=voiceDucked?volumeBeforeVoice:audio.Volume;duckTimer.Stop();voiceDucked=false;audio.Volume=Math.Max(0,Math.Min(1,basis+delta/100f));RefreshAudio();}
        static string Shorten(string value,int max){value=value.Trim();return value.Length<=max?value:value.Substring(0,max-1)+"…";}
        void SaveLocation(){try{using(RegistryKey key=Registry.CurrentUser.CreateSubKey(@"Software\AudioPersonal")){key.SetValue("X",Location.X);key.SetValue("Y",Location.Y);}}catch{}}
        void SetCombinedCompact(bool compact,bool save){if(synchronizingCompact)return;synchronizingCompact=true;try{SetPanelCompact(compact,save);if(playerForm!=null&&!playerForm.IsDisposed)playerForm.ApplyCompact(compact,save);}finally{synchronizingCompact=false;}if(playerForm!=null&&playerForm.Visible)MovePairFromPlayer();}
        void SetPanelCompact(bool compact,bool save){compactPanel=compact;fader.CompactStyle=compact;resize.Text=compact?"□":"▣";int target=(playerForm!=null&&!playerForm.IsDisposed&&playerForm.Visible)?playerForm.Height:ExpectedPlayerHeight(compact);SetPanelHeight(target);if(save)try{using(RegistryKey key=Registry.CurrentUser.CreateSubKey(@"Software\AudioPersonal"))key.SetValue("PanelCompact",compact?1:0);}catch{}Invalidate(true);}
        static int ExpectedPlayerHeight(bool compact){return (compact?185:640)+SystemInformation.CaptionHeight+SystemInformation.FixedFrameBorderSize.Height*2;}
        void SetPanelHeight(int height){height=Math.Max(compactPanel?205:490,height);MinimumSize=Size.Empty;MaximumSize=Size.Empty;Size=new Size(174,height);if(compactPanel){fader.SetBounds(20,40,45,125);meter.SetBounds(94,40,60,125);balanceText.Visible=balance.Visible=false;mute.SetBounds(44,170,86,27);voiceState.SetBounds(9,201,156,Math.Max(9,height-205));}else{int statusY=height-90,muteY=height-129,balanceY=height-171,balanceLabelY=height-193;fader.SetBounds(10,47,88,Math.Max(245,balanceLabelY-52));meter.SetBounds(104,47,60,Math.Max(245,balanceLabelY-52));balanceText.SetBounds(10,balanceLabelY,154,20);balance.SetBounds(17,balanceY,140,32);balanceText.Visible=balance.Visible=true;mute.SetBounds(44,muteY,86,34);voiceState.SetBounds(9,statusY,156,80);}MinimumSize=MaximumSize=Size;if(IsHandleCreated)AppearanceManager.ApplyRounded(this);}
        void RefreshAudio(){if(audio==null)return;int value=Math.Max(0,Math.Min(100,(int)Math.Round(audio.Volume*100)));internalChange=true;fader.Value=value;percent.Text=value+"%";mute.Text=audio.Muted||value==0?"MUTE ●":"MUTE";mute.BackColor=audio.Muted?Color.FromArgb(190,45,45):BackColor;float l,r;audio.GetPeaks(out l,out r);meter.SetLevels(l,r);internalChange=false;}
        void ApplyTheme(){AppearancePalette palette=AppearanceManager.Palette;BackColor=palette.Background;ForeColor=palette.Text;foreach(Control c in Controls){c.BackColor=palette.Background;c.ForeColor=palette.Text;}foreach(Button button in new[]{mute,minimize,music,resize,view})AppearanceManager.StyleButton(button,palette);AppearanceManager.ApplyRoundedControl(voiceState,12);fader.DarkTheme=meter.DarkTheme=balance.DarkTheme=palette.Dark;Invalidate(true);}
        void AppearanceChanged(object sender,EventArgs e){ApplyTheme();}
        void ThemeChanged(object sender,UserPreferenceChangedEventArgs e){AppearanceManager.RefreshWindowsTheme();ApplyTheme();}void DragWindow(object sender,MouseEventArgs e){if(e.Button==MouseButtons.Left){Native.ReleaseCapture();Native.SendMessage(Handle,0xA1,new IntPtr(2),IntPtr.Zero);}}
        protected override void Dispose(bool disposing){if(disposing){RestoreAfterVoice();refresh.Dispose();duckTimer.Dispose();tray.Dispose();if(audio!=null)audio.Dispose();if(musicForm!=null)musicForm.Dispose();if(playerForm!=null)playerForm.Dispose();playerEngine.Dispose();SystemEvents.UserPreferenceChanged-=ThemeChanged;AppearanceManager.Changed-=AppearanceChanged;}base.Dispose(disposing);}
    }

    internal sealed class ConsoleFader:Control
    {
        int value=50;bool dragging,dark,compactStyle;public event EventHandler ValueChanged;public bool DarkTheme{get{return dark;}set{dark=value;Invalidate();}}public bool CompactStyle{get{return compactStyle;}set{compactStyle=value;Invalidate();}}public int Value{get{return value;}set{int v=Math.Max(0,Math.Min(100,value));if(this.value!=v){this.value=v;Invalidate();}}}public ConsoleFader(){DoubleBuffered=true;Cursor=Cursors.Hand;}
        protected override void OnPaint(PaintEventArgs e){Graphics g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;int top=14,bottom=Height-14,cx=Width/2;using(Pen rail=new Pen(dark?Color.Black:Color.FromArgb(55,55,55),compactStyle?6:8))g.DrawLine(rail,cx,top,cx,bottom);using(Pen edge=new Pen(dark?Color.FromArgb(85,85,85):Color.FromArgb(185,185,180),1))for(int i=0;i<=10;i++){int y=top+(bottom-top)*i/10;g.DrawLine(edge,compactStyle?2:6,y,compactStyle?8:19,y);g.DrawLine(edge,compactStyle?Width-9:Width-20,y,compactStyle?Width-3:Width-7,y);}int knobY=bottom-(bottom-top)*value/100,half=compactStyle?13:22,left=compactStyle?5:8;Rectangle knob=new Rectangle(left,knobY-half,Width-left*2,half*2);using(LinearGradientBrush b=new LinearGradientBrush(knob,dark?Color.FromArgb(115,115,115):Color.FromArgb(150,150,145),dark?Color.FromArgb(48,48,48):Color.FromArgb(78,78,75),LinearGradientMode.Vertical))g.FillRectangle(b,knob);using(Pen p=new Pen(Color.FromArgb(35,35,35),1))g.DrawRectangle(p,knob);using(Pen groove=new Pen(Color.FromArgb(45,45,45),compactStyle?1:2))for(int i=compactStyle?-8:-12;i<=(compactStyle?8:12);i+=compactStyle?4:6)g.DrawLine(groove,knob.Left+(compactStyle?5:7),knobY+i,knob.Right-(compactStyle?5:7),knobY+i);Color mark=Blend(Color.FromArgb(255,240,155),Color.FromArgb(255,118,118),value/100f);using(Pen pen=new Pen(mark,3))g.DrawLine(pen,knob.Left+3,knobY,knob.Right-3,knobY);}
        static Color Blend(Color from,Color to,float amount){amount=Math.Max(0,Math.Min(1,amount));return Color.FromArgb((int)(from.R+(to.R-from.R)*amount),(int)(from.G+(to.G-from.G)*amount),(int)(from.B+(to.B-from.B)*amount));}
        void UpdateValue(int y){int top=14,bottom=Height-14;Value=(int)Math.Round(100.0*(bottom-Math.Max(top,Math.Min(bottom,y)))/(bottom-top));EventHandler h=ValueChanged;if(h!=null)h(this,EventArgs.Empty);}protected override void OnMouseDown(MouseEventArgs e){if(e.Button==MouseButtons.Left){dragging=true;Capture=true;UpdateValue(e.Y);}}protected override void OnMouseMove(MouseEventArgs e){if(dragging)UpdateValue(e.Y);}protected override void OnMouseUp(MouseEventArgs e){dragging=false;Capture=false;}protected override void OnMouseWheel(MouseEventArgs e){Value+=e.Delta>0?2:-2;EventHandler h=ValueChanged;if(h!=null)h(this,EventArgs.Empty);}
    }
    internal sealed class StereoMeter:Control
    {
        float shownL,shownR,peakL,peakR;int holdL,holdR;bool dark;public bool DarkTheme{get{return dark;}set{dark=value;Invalidate();}}public StereoMeter(){DoubleBuffered=true;}
        public void SetLevels(float l,float r){l=Math.Max(0,Math.Min(1,l));r=Math.Max(0,Math.Min(1,r));shownL=l>=shownL?l:Math.Max(l,shownL-0.025f);shownR=r>=shownR?r:Math.Max(r,shownR-0.025f);if(l>=peakL){peakL=l;holdL=18;}else if(holdL--<=0)peakL=Math.Max(l,peakL-0.012f);if(r>=peakR){peakR=r;holdR=18;}else if(holdR--<=0)peakR=Math.Max(r,peakR-0.012f);Invalidate();}
        protected override void OnPaint(PaintEventArgs e){Graphics g=e.Graphics;g.Clear(dark?Color.FromArgb(13,16,14):Color.FromArgb(50,54,50));DrawBar(g,6,shownL,peakL);DrawBar(g,32,shownR,peakR);using(Font f=new Font("Segoe UI",7F,FontStyle.Bold))using(Brush b=new SolidBrush(Color.Silver)){g.DrawString("L",f,b,9,Height-16);g.DrawString("R",f,b,35,Height-16);}}
        void DrawBar(Graphics g,int x,float level,float peak){int top=7,bottom=Height-21,w=19,gap=2,segments=30;for(int i=0;i<segments;i++){float p=(i+1)/(float)segments;int y=bottom-(i+1)*(bottom-top)/segments;int h=Math.Max(2,(bottom-top)/segments-gap);Color active=p>.90f?Color.FromArgb(235,45,45):p>.72f?Color.FromArgb(245,157,35):p>.45f?Color.FromArgb(38,210,78):Color.FromArgb(92,190,100);Color off=dark?Color.FromArgb(25,43,29):Color.FromArgb(38,66,42);using(Brush b=new SolidBrush(p<=level?active:off))g.FillRectangle(b,x,y,w,h);}int py=bottom-(int)((bottom-top)*peak);using(Pen p=new Pen(Color.White,1))g.DrawLine(p,x,py,x+w,py);}
    }
    internal sealed class BalanceControl:Control
    {
        int value;bool dragging,dark;public event EventHandler ValueChanged;public bool DarkTheme{get{return dark;}set{dark=value;Invalidate();}}public int Value{get{return value;}set{this.value=Math.Max(-100,Math.Min(100,value));Invalidate();}}public BalanceControl(){DoubleBuffered=true;Cursor=Cursors.Hand;}
        protected override void OnPaint(PaintEventArgs e){Graphics g=e.Graphics;g.SmoothingMode=SmoothingMode.AntiAlias;int cy=Height/2,left=11,right=Width-11;using(Pen p=new Pen(dark?Color.FromArgb(8,10,11):Color.FromArgb(55,55,55),6))g.DrawLine(p,left,cy,right,cy);using(Pen tick=new Pen(dark?Color.FromArgb(92,101,105):Color.FromArgb(130,130,125),1))for(int i=0;i<=10;i++){int px=left+(right-left)*i/10;g.DrawLine(tick,px,2,px,5);g.DrawLine(tick,px,Height-6,px,Height-3);}int center=Width/2;using(Pen marker=new Pen(Color.FromArgb(255,220,25),2))g.DrawLine(marker,center,4,center,Height-4);int x=left+(right-left)*(value+100)/200;Rectangle knob=new Rectangle(x-10,cy-8,20,16);using(LinearGradientBrush body=new LinearGradientBrush(knob,Color.FromArgb(132,137,137),Color.FromArgb(49,53,54),LinearGradientMode.Horizontal))g.FillRectangle(body,knob);using(Pen outline=new Pen(Color.FromArgb(16,18,19),1))g.DrawRectangle(outline,knob);using(Pen groove=new Pen(Color.FromArgb(35,38,39),1))for(int offset=-6;offset<=6;offset+=4)g.DrawLine(groove,x+offset,knob.Top+3,x+offset,knob.Bottom-3);Color line=value<0?Blend(Color.FromArgb(255,220,25),Color.FromArgb(20,95,255),Math.Abs(value)/100f):Blend(Color.FromArgb(255,220,25),Color.FromArgb(245,35,35),Math.Abs(value)/100f);using(Pen colored=new Pen(line,3))g.DrawLine(colored,x,knob.Top+1,x,knob.Bottom-1);}
        static Color Blend(Color from,Color to,float amount){amount=Math.Max(0,Math.Min(1,amount));return Color.FromArgb((int)(from.R+(to.R-from.R)*amount),(int)(from.G+(to.G-from.G)*amount),(int)(from.B+(to.B-from.B)*amount));}
        void UpdateValue(int x){Value=(int)Math.Round(200.0*(Math.Max(8,Math.Min(Width-8,x))-8)/(Width-16)-100);if(Math.Abs(Value)<6)Value=0;EventHandler h=ValueChanged;if(h!=null)h(this,EventArgs.Empty);}protected override void OnMouseDown(MouseEventArgs e){if(e.Button==MouseButtons.Left){dragging=true;Capture=true;UpdateValue(e.X);}}protected override void OnMouseMove(MouseEventArgs e){if(dragging)UpdateValue(e.X);}protected override void OnMouseUp(MouseEventArgs e){dragging=false;Capture=false;}protected override void OnDoubleClick(EventArgs e){Value=0;EventHandler h=ValueChanged;if(h!=null)h(this,EventArgs.Empty);}
    }
    internal static class Native{[DllImport("user32.dll")]internal static extern bool ReleaseCapture();[DllImport("user32.dll")]internal static extern IntPtr SendMessage(IntPtr hWnd,int msg,IntPtr wParam,IntPtr lParam);[DllImport("dwmapi.dll")]internal static extern int DwmSetWindowAttribute(IntPtr hwnd,int attribute,ref int value,int size);}
    internal static class Startup{public static void Enable(){try{using(RegistryKey key=Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run")){key.SetValue("AudioPersonal","\""+Application.ExecutablePath+"\"");key.DeleteValue("PoteVolumen",false);}}catch{}}}
    internal sealed class AudioDevice:IDisposable
    {
        readonly IAudioEndpointVolume endpoint;readonly IAudioMeterInformation meter;readonly uint channels;AudioDevice(IAudioEndpointVolume e,IAudioMeterInformation m){endpoint=e;meter=m;endpoint.GetChannelCount(out channels);}public float Volume{get{float v;endpoint.GetMasterVolumeLevelScalar(out v);return v;}set{endpoint.SetMasterVolumeLevelScalar(value,Guid.Empty);}}public bool Muted{get{bool m;endpoint.GetMute(out m);return m;}set{endpoint.SetMute(value,Guid.Empty);}}
        public void GetPeaks(out float l,out float r){l=r=0;try{uint count;meter.GetMeteringChannelCount(out count);float[] values=new float[Math.Max(1,(int)count)];meter.GetChannelsPeakValues(count,values);l=values[0];r=count>1?values[1]:l;}catch{try{float p;meter.GetPeakValue(out p);l=r=p;}catch{l=r=0;}}}
        public void SetBalance(int balance)
        {
            if(channels<2)return;
            float master=Volume,l,r;
            CalculateBalanceLevels(master,balance,out l,out r);
            endpoint.SetChannelVolumeLevelScalar(0,l,Guid.Empty);
            endpoint.SetChannelVolumeLevelScalar(1,r,Guid.Empty);
        }
        internal static void CalculateBalanceLevels(float master,int balance,out float left,out float right)
        {
            master=Math.Max(0f,Math.Min(1f,master));
            balance=Math.Max(-100,Math.Min(100,balance));
            left=master*(balance<=0?1f:(100-balance)/100f);
            right=master*(balance>=0?1f:(100+balance)/100f);
        }
        public static AudioDevice TryCreate(){try{IMMDeviceEnumeratorVolume en=(IMMDeviceEnumeratorVolume)new MMDeviceEnumeratorVolume();IMMDeviceVolume dev;en.GetDefaultAudioEndpoint(0,1,out dev);object a,m;Guid aid=typeof(IAudioEndpointVolume).GUID,mid=typeof(IAudioMeterInformation).GUID;dev.Activate(ref aid,23,IntPtr.Zero,out a);dev.Activate(ref mid,23,IntPtr.Zero,out m);Marshal.ReleaseComObject(dev);Marshal.ReleaseComObject(en);return new AudioDevice((IAudioEndpointVolume)a,(IAudioMeterInformation)m);}catch{return null;}}public void Dispose(){if(endpoint!=null)Marshal.ReleaseComObject(endpoint);if(meter!=null)Marshal.ReleaseComObject(meter);}
    }
    [ComImport,Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]internal class MMDeviceEnumeratorVolume{}
    [ComImport,InterfaceType(ComInterfaceType.InterfaceIsIUnknown),Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]internal interface IMMDeviceEnumeratorVolume{int NotImpl1();[PreserveSig]int GetDefaultAudioEndpoint(int flow,int role,out IMMDeviceVolume device);}
    [ComImport,InterfaceType(ComInterfaceType.InterfaceIsIUnknown),Guid("D666063F-1587-4E43-81F1-B948E807363F")]internal interface IMMDeviceVolume{[PreserveSig]int Activate(ref Guid iid,int context,IntPtr parameters,[MarshalAs(UnmanagedType.IUnknown)]out object iface);}
    [ComImport,InterfaceType(ComInterfaceType.InterfaceIsIUnknown),Guid("5CDF2C82-841E-4546-9722-0CF74078229A")]internal interface IAudioEndpointVolume{int RegisterControlChangeNotify(IntPtr p);int UnregisterControlChangeNotify(IntPtr p);int GetChannelCount(out uint n);int SetMasterVolumeLevel(float n,Guid g);int SetMasterVolumeLevelScalar(float n,Guid g);int GetMasterVolumeLevel(out float n);int GetMasterVolumeLevelScalar(out float n);int SetChannelVolumeLevel(uint c,float n,Guid g);int SetChannelVolumeLevelScalar(uint c,float n,Guid g);int GetChannelVolumeLevel(uint c,out float n);int GetChannelVolumeLevelScalar(uint c,out float n);int SetMute([MarshalAs(UnmanagedType.Bool)]bool m,Guid g);int GetMute([MarshalAs(UnmanagedType.Bool)]out bool m);}
    [ComImport,InterfaceType(ComInterfaceType.InterfaceIsIUnknown),Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064")]internal interface IAudioMeterInformation{int GetPeakValue(out float peak);int GetMeteringChannelCount(out uint count);int GetChannelsPeakValues(uint count,[Out,MarshalAs(UnmanagedType.LPArray,SizeParamIndex=0)]float[] values);int QueryHardwareSupport(out uint mask);}

    sealed class MainForm : Form
    {
        readonly Label status = new Label(), partial = new Label(), catalogStatus = new Label();
        readonly TextBox transcript = new TextBox(), musicFolder = new TextBox(), player = new TextBox();
        readonly ProgressBar progress = new ProgressBar();
        readonly ComboBox microphones = new ComboBox();
        readonly Button install = new Button(), start = new Button(), stop = new Button(), viewButton = new Button();
        readonly Button chooseFolder = new Button(), indexMusic = new Button(), choosePlayer = new Button(), defaultPlayer = new Button();
        readonly CheckBox executeCommands = new CheckBox();
        readonly MusicCatalog catalog = new MusicCatalog();
        readonly AppSettings settings = AppSettings.Load();
        readonly Action<string> compactStatus;
        readonly Action duckAudio, restoreAudio;
        readonly Action<int> setVolume, adjustVolume;
        readonly InternalPlayerEngine playerEngine;
        readonly Action openPlayer, closePlayer;
        VoskSession session;
        DateTime awakeUntil = DateTime.MinValue;
        int searchSerial;
        bool initialized;
        public bool HasMusicFolder { get { return Directory.Exists(settings.MusicFolder); } }
        public void Initialize(){if(initialized)return;initialized=true;IntPtr windowHandle=Handle;if(!String.IsNullOrEmpty(settings.MusicFolder))LoadOrBuildCatalog(false);}

        public MainForm(Action<string> compactStatus, Action duckAudio, Action restoreAudio, Action<int> setVolume, Action<int> adjustVolume, InternalPlayerEngine playerEngine, Action openPlayer, Action closePlayer)
        {
            this.compactStatus = compactStatus;
            this.duckAudio = duckAudio;
            this.restoreAudio = restoreAudio;
            this.setVolume = setVolume;
            this.adjustVolume = adjustVolume;
            this.playerEngine = playerEngine;
            this.openPlayer = openPlayer;
            this.closePlayer = closePlayer;
            Text = "Audio Personal - Configuración";
            ClientSize = new Size(860, 675); StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Segoe UI", 10F); MinimumSize = new Size(760, 610);
            viewButton.Text="Ver ▾";viewButton.SetBounds(20,16,58,30);viewButton.Click+=delegate{ContextMenuStrip menu=new ContextMenuStrip();menu.Items.Add("Apariencia...",null,delegate{using(AppearanceForm dialog=new AppearanceForm())dialog.ShowDialog(this);});menu.Show(viewButton,new Point(0,viewButton.Height));};
            Label title = NewLabel("Audio Personal — catálogo y reproducción por voz", 88, 14, 752, 34);
            title.Font = new Font("Segoe UI", 15F, FontStyle.Bold);
            status.Text = Installer.Ready ? "Reconocimiento bilingüe instalado." : "Falta instalar el reconocimiento bilingüe.";
            status.SetBounds(20, 54, 820, 44); status.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            status.BorderStyle = BorderStyle.FixedSingle; status.TextAlign = ContentAlignment.MiddleCenter;
            progress.SetBounds(20, 106, 820, 20); progress.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;

            install.Text = "Instalar español + inglés"; install.SetBounds(20, 138, 205, 34); install.Click += InstallClick;
            start.Text = "Iniciar escucha"; start.SetBounds(237, 138, 140, 34); start.Enabled = Installer.Ready; start.Click += StartClick;
            stop.Text = "Detener"; stop.SetBounds(389, 138, 105, 34); stop.Enabled = false; stop.Click += delegate { StopVoice(); };
            executeCommands.Text = "Ejecutar órdenes"; executeCommands.Checked = true; executeCommands.SetBounds(510, 143, 145, 25);

            Label micLabel = NewLabel("Micrófono:", 20, 188, 90, 26);
            microphones.DropDownStyle = ComboBoxStyle.DropDownList; microphones.SetBounds(112, 185, 728, 28);
            microphones.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right; LoadMicrophones();

            Label folderLabel = NewLabel("Carpeta MP3:", 20, 231, 105, 26);
            musicFolder.ReadOnly = true; musicFolder.Text = settings.MusicFolder; musicFolder.SetBounds(126, 228, 472, 28);
            musicFolder.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            chooseFolder.Text = "Seleccionar"; chooseFolder.SetBounds(610, 226, 105, 32); chooseFolder.Anchor = AnchorStyles.Top | AnchorStyles.Right; chooseFolder.Click += ChooseFolderClick;
            indexMusic.Text = "Crear índice"; indexMusic.SetBounds(727, 226, 113, 32); indexMusic.Anchor = AnchorStyles.Top | AnchorStyles.Right; indexMusic.Click += IndexClick;

            Label playerLabel = NewLabel("Reproductor:", 20, 272, 105, 26);
            player.ReadOnly = true; player.Text = "Audio Personal interno — WASAPI + ecualizador";
            player.SetBounds(126, 269, 472, 28); player.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            choosePlayer.Text = "Abrir player"; choosePlayer.SetBounds(610, 267, 105, 32); choosePlayer.Anchor = AnchorStyles.Top | AnchorStyles.Right; choosePlayer.Click += delegate { if(this.openPlayer!=null)this.openPlayer(); };
            defaultPlayer.Text = "INTERNO"; defaultPlayer.SetBounds(727, 267, 113, 32); defaultPlayer.Anchor = AnchorStyles.Top | AnchorStyles.Right; defaultPlayer.Enabled=false;

            catalogStatus.Text = "Catálogo: todavía no cargado."; catalogStatus.SetBounds(20, 311, 820, 25);
            catalogStatus.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            partial.Text = "Aquí aparecerá en vivo lo que Vosk entienda en español.";
            partial.SetBounds(20, 344, 820, 46); partial.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            partial.BorderStyle = BorderStyle.FixedSingle; partial.TextAlign = ContentAlignment.MiddleLeft; partial.Padding = new Padding(8, 0, 8, 0);
            transcript.Multiline = true; transcript.ScrollBars = ScrollBars.Vertical; transcript.ReadOnly = true;
            transcript.Font = new Font("Segoe UI", 11.5F); transcript.SetBounds(20, 402, 820, 248);
            transcript.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
            transcript.Text = "Actividad de Audio Personal:\r\n";

            Controls.AddRange(new Control[] { viewButton, title, status, progress, install, start, stop, executeCommands,
                micLabel, microphones, folderLabel, musicFolder, chooseFolder, indexMusic, playerLabel, player,
                choosePlayer, defaultPlayer, catalogStatus, partial, transcript });
            AppearanceManager.Changed+=AppearanceChanged;ApplyAppearance();
            FormClosing += delegate(object sender, FormClosingEventArgs e) { if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); } };
            Shown += delegate { AppearanceManager.ApplyRounded(this);Initialize(); };
        }

        Label NewLabel(string value, int x, int y, int width, int height)
        { Label label = new Label(); label.Text = value; label.SetBounds(x, y, width, height); return label; }
        void AppearanceChanged(object sender,EventArgs e){ApplyAppearance();}
        void ApplyAppearance()
        {
            AppearancePalette palette=AppearanceManager.Palette;BackColor=palette.Background;ForeColor=palette.Text;
            foreach(Control control in Controls)
            {
                Button button=control as Button;if(button!=null){AppearanceManager.StyleButton(button,palette);continue;}
                control.ForeColor=palette.Text;if(control is Label||control is CheckBox)control.BackColor=palette.Background;
                else if(control is TextBox||control is ComboBox){control.BackColor=palette.Surface;control.ForeColor=palette.Text;AppearanceManager.ApplyRoundedControl(control,8);}
                Label label=control as Label;if(label!=null&&label.BorderStyle!=BorderStyle.None)AppearanceManager.ApplyRoundedControl(label,10);
            }
            Invalidate(true);
        }

        void LoadMicrophones()
        {
            microphones.Items.Clear(); string name = AudioDevices.GetDefaultCaptureName();
            microphones.Items.Add(new MicrophoneItem(-1, "Predeterminado de Windows" + (String.IsNullOrEmpty(name) ? "" : " — " + name)));
            foreach (MicrophoneItem item in WaveInput.ListDevices()) microphones.Items.Add(item); microphones.SelectedIndex = 0;
        }

        void InstallClick(object sender, EventArgs e)
        {
            if (MessageBox.Show("Se descargarán los modelos oficiales pequeños de español e inglés. El modelo español ya instalado se reutilizará. ¿Continuar?",
                "Reconocimiento bilingüe", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            install.Enabled = start.Enabled = false;
            RunBackground(delegate { Installer.Install(UpdateProgress); }, delegate
            { SetStatus("Reconocimiento bilingüe listo."); progress.Value = 100; start.Enabled = catalog.Count > 0; install.Enabled = false; if (catalog.Count > 0) StartListening(); });
        }

        void ChooseFolderClick(object sender, EventArgs e)
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "Seleccione la carpeta contenedora de su música";
                dialog.SelectedPath = settings.MusicFolder;
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                settings.MusicFolder = dialog.SelectedPath; settings.Save(); musicFolder.Text = settings.MusicFolder; LoadOrBuildCatalog(true);
            }
        }

        void IndexClick(object sender, EventArgs e)
        {
            if (String.IsNullOrEmpty(settings.MusicFolder)) { ChooseFolderClick(sender, e); return; }
            LoadOrBuildCatalog(true);
        }

        void LoadOrBuildCatalog(bool rebuild)
        {
            if (!Directory.Exists(settings.MusicFolder)) { catalogStatus.Text = "La carpeta configurada no existe."; return; }
            if (session != null) StopVoice();
            chooseFolder.Enabled = indexMusic.Enabled = false; start.Enabled = false;
            RunBackground(delegate { catalog.Load(settings.MusicFolder, rebuild, CatalogProgress); }, delegate
            {
                catalogStatus.Text = "Catálogo listo: " + catalog.Count.ToString("N0") + " canciones" +
                    (catalog.SkippedCount == 0 ? "." : "; omitidos " + catalog.SkippedCount + " archivos problemáticos.");
                chooseFolder.Enabled = indexMusic.Enabled = true; start.Enabled = Installer.Ready;
                if (Installer.Ready) StartListening();
            });
        }

        void CatalogProgress(int count, string message)
        { Ui(delegate { catalogStatus.Text = message + "  " + count.ToString("N0"); }); }

        void StartClick(object sender, EventArgs e)
        { StartListening(); }

        void StartListening()
        {
            try
            {
                if (session != null) return;
                if (catalog.Count == 0) { MessageBox.Show("Primero seleccione la carpeta MP3 y espere a que termine el catálogo.", "Audio Personal"); return; }
                MicrophoneItem selected = microphones.SelectedItem as MicrophoneItem;
                session = new VoskSession(); session.PartialReceived += PartialReceived; session.TextReceived += TextReceived; session.Failed += SessionFailed;
                session.Start(selected == null ? -1 : selected.Id);
                SetStatus("Escuchando en español e inglés por: " + (selected == null ? "Windows" : selected.Name));
                partial.Text = "Diga: computadora, Beatles; o computadora, pausa.";
                start.Enabled = false; stop.Enabled = true; microphones.Enabled = false;
            }
            catch (Exception ex) { StopVoice(); SetStatus("No se pudo iniciar: " + ex.Message); }
        }

        void PartialReceived(string value)
        { Ui(delegate { partial.Text = String.IsNullOrWhiteSpace(value) ? "Escuchando..." : value;if(MusicRequest.HasWake(value)){if(duckAudio!=null)duckAudio();SetStatus("Te escucho... Volumen temporal al 10%.");} }); }

        void TextReceived(RecognitionResult result)
        {
            if (String.IsNullOrWhiteSpace(result.Spanish)) return;
            Ui(delegate
            {
                Append("Escuché: " + result.Spanish);
                if(MusicRequest.HasWake(result.Spanish)&&duckAudio!=null)duckAudio();
                string playerAction=executeCommands.Checked?PlayerCommandProcessor.TryExecute(result.Spanish,ref awakeUntil,playerEngine,openPlayer,closePlayer):null;
                string volumeAction=executeCommands.Checked?CommandProcessor.TryExecute(result.Spanish,ref awakeUntil,setVolume,adjustVolume):null;
                if(!String.IsNullOrEmpty(playerAction)||!String.IsNullOrEmpty(volumeAction))
                {
                    awakeUntil=DateTime.MinValue;
                    if(restoreAudio!=null&&String.IsNullOrEmpty(volumeAction))restoreAudio();
                    SetStatus(String.IsNullOrEmpty(playerAction)?volumeAction:(String.IsNullOrEmpty(volumeAction)?playerAction:volumeAction+" "+playerAction));return;
                }
                string query = MusicRequest.Extract(result.Spanish, ref awakeUntil);
                if (!String.IsNullOrEmpty(query))
                {
                    int serial = Interlocked.Increment(ref searchSerial); string english = result.English;
                    SetStatus("Buscando: " + query);
                    ThreadPool.QueueUserWorkItem(delegate
                    {
                        TrackMatch match = null;Exception searchError=null;try{match=catalog.FindBest(query,english);}catch(Exception ex){searchError=ex;}
                        Ui(delegate
                        {
                            if (serial != searchSerial) return;
                            string found;
                            if(searchError!=null)found="Error al buscar la canción: "+searchError.Message;
                            else if (match != null)
                            {
                                try { if(playerEngine.Play(match.Track)){if(openPlayer!=null)openPlayer();found = match.ArtistFallback ? "Título no reconocido; reproduciendo un tema de " + match.Track.Artist + ": " + match.Track.Title : "Reproduciendo: " + match.Track.DisplayName;Append("Coincidencia: "+match.Track.DisplayName+" ("+Math.Max(0,Math.Min(100,(int)Math.Round(match.Score*100)))+"%)");Append(found);}else found="No se pudo reproducir: "+match.Track.DisplayName; }
                                catch (Exception ex) { found = "No se pudo abrir la canción: " + ex.Message; }
                            }
                            else found = "No encontré una coincidencia clara para: " + query;
                            if(restoreAudio!=null)restoreAudio();
                            SetStatus(found);
                        });
                    });
                    return;
                }
                SetStatus("Escuchando: computadora");
            });
        }

        void Append(string value)
        {
            transcript.AppendText(DateTime.Now.ToString("HH:mm:ss") + "  " + value + Environment.NewLine);
            if (transcript.TextLength > 30000) transcript.Text = transcript.Text.Substring(transcript.TextLength - 22000);
            transcript.SelectionStart = transcript.TextLength; transcript.ScrollToCaret();
        }

        void SessionFailed(Exception ex)
        { Ui(delegate { SetStatus("La escucha se detuvo: " + ex.Message); StopVoice(); }); }

        void StopVoice()
        {
            VoskSession old = session; session = null; if (old != null) old.Dispose();
            if(restoreAudio!=null)restoreAudio();
            start.Enabled = Installer.Ready; stop.Enabled = false; microphones.Enabled = true; partial.Text = "Escucha detenida.";
            SetStatus("Escucha detenida.");
        }

        void RunBackground(Action work, MethodInvoker completed)
        {
            Thread thread = new Thread(new ThreadStart(delegate
            {
                try { work(); Ui(completed); }
                catch (Exception ex) { Ui(delegate { SetStatus("Error: " + ex.Message); install.Enabled = true; chooseFolder.Enabled = indexMusic.Enabled = true; start.Enabled = Installer.Ready && catalog.Count > 0; }); }
            })); thread.IsBackground = true; thread.Start();
        }

        void UpdateProgress(int value, string message)
        { Ui(delegate { progress.Value = Math.Max(0, Math.Min(100, value)); SetStatus(message); }); }

        void SetStatus(string message) { status.Text = message; if (compactStatus != null) compactStatus(message); }

        void Ui(MethodInvoker action)
        { if (IsDisposed || !IsHandleCreated) return; try { BeginInvoke(action); } catch { } }

        protected override void Dispose(bool disposing) { if (disposing){StopVoice();AppearanceManager.Changed-=AppearanceChanged;} base.Dispose(disposing); }
    }

    sealed class MicrophoneItem
    {
        public readonly int Id;
        public readonly string Name;
        public MicrophoneItem(int id, string name) { Id = id; Name = name; }
        public override string ToString() { return Name; }
    }

    static class Installer
    {
        public static readonly string Root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "AudioPersonal", "Vosk");
        public static readonly string Engine = Path.Combine(Root, "engine");
        public static readonly string SpanishModel = Path.Combine(Root, "models", "vosk-model-small-es-0.42");
        public static readonly string EnglishModel = Path.Combine(Root, "models", "vosk-model-small-en-us-0.15");
        static readonly string[] NativeFiles = { "libvosk.dll", "libstdc++-6.dll", "libwinpthread-1.dll", "libgcc_s_seh-1.dll" };
        const string EngineUrl = "https://api.nuget.org/v3-flatcontainer/vosk/0.3.38/vosk.0.3.38.nupkg";
        const string SpanishUrl = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip";
        const string EnglishUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";

        public static bool Ready
        {
            get
            {
                foreach (string file in NativeFiles)
                    if (!File.Exists(Path.Combine(Engine, file))) return false;
                return ModelReady(SpanishModel) && ModelReady(EnglishModel);
            }
        }

        public static void Install(Action<int, string> report)
        {
            Directory.CreateDirectory(Root);
            Directory.CreateDirectory(Engine);
            Directory.CreateDirectory(Path.Combine(Root, "models"));
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;

            string package = Path.Combine(Root, "vosk.0.3.38.nupkg");
            if (!EngineReady())
            {
                Download(EngineUrl, package, 0, 15, "Descargando motor Vosk...", 10000000, report);
                report(16, "Instalando motor Vosk..."); InstallEngine(package);
            }
            else report(16, "Motor Vosk ya instalado.");
            InstallModel("vosk-model-small-es-0.42", SpanishUrl, SpanishModel, 18, 55, "español", report);
            InstallModel("vosk-model-small-en-us-0.15", EnglishUrl, EnglishModel, 57, 97, "inglés", report);
            if (!Ready) throw new Exception("La instalación quedó incompleta. Puede volver a pulsar Instalar para repararla.");
            report(100, "Vosk bilingüe está listo.");
        }

        static bool ModelReady(string path)
        { return Directory.Exists(path) && File.Exists(Path.Combine(path, "am", "final.mdl")); }

        static bool EngineReady()
        {
            foreach (string file in NativeFiles) if (!File.Exists(Path.Combine(Engine, file))) return false;
            return true;
        }

        static void InstallModel(string name, string url, string destination, int from, int to,
            string language, Action<int, string> report)
        {
            if (ModelReady(destination)) { report(to, "Modelo " + language + " ya instalado."); return; }
            string zip = Path.Combine(Root, name + ".zip");
            Download(url, zip, from, to - 3, "Descargando modelo " + language + "...", 10000000, report);
            report(to - 2, "Instalando modelo " + language + "...");
            if (ModelReady(destination)) return;
            string parent = Path.Combine(Root, "models");
            if (Directory.Exists(destination)) Directory.Delete(destination, true);
            try { ZipFile.ExtractToDirectory(zip, parent); }
            catch
            {
                if (Directory.Exists(destination)) Directory.Delete(destination, true);
                if (File.Exists(zip)) File.Delete(zip);
                throw;
            }
        }

        static void InstallEngine(string package)
        {
            bool complete = true;
            foreach (string file in NativeFiles)
                if (!File.Exists(Path.Combine(Engine, file))) complete = false;
            if (complete) return;

            string unpacked = Path.Combine(Root, "package-0.3.38");
            string source = Path.Combine(unpacked, "build", "lib", "win-x64");
            if (Directory.Exists(unpacked) && !Directory.Exists(source)) Directory.Delete(unpacked, true);
            if (!Directory.Exists(unpacked))
            {
                try { ZipFile.ExtractToDirectory(package, unpacked); }
                catch
                {
                    if (Directory.Exists(unpacked)) Directory.Delete(unpacked, true);
                    if (File.Exists(package)) File.Delete(package);
                    throw;
                }
            }
            if (!Directory.Exists(source)) throw new Exception("El paquete oficial no contiene el motor de Windows x64.");
            foreach (string file in NativeFiles)
                File.Copy(Path.Combine(source, file), Path.Combine(Engine, file), true);
        }

        static void Download(string url, string path, int from, int to, string message,
            long minimumBytes, Action<int, string> report)
        {
            if (File.Exists(path) && new FileInfo(path).Length >= minimumBytes)
            {
                report(to, message + " listo");
                return;
            }
            string temporary = path + ".descarga";
            if (File.Exists(temporary)) File.Delete(temporary);
            using (WebClient client = new WebClient())
            {
                client.Headers.Add("User-Agent", "AudioPersonal/4.12.4-beta");
                client.DownloadProgressChanged += delegate(object sender, DownloadProgressChangedEventArgs e)
                {
                    report(from + ((to - from) * e.ProgressPercentage / 100),
                        message + " " + e.ProgressPercentage + "%");
                };
                client.DownloadFileTaskAsync(new Uri(url), temporary).GetAwaiter().GetResult();
            }
            if (!File.Exists(temporary) || new FileInfo(temporary).Length < minimumBytes)
                throw new Exception("La descarga recibida está incompleta.");
            if (File.Exists(path)) File.Delete(path);
            File.Move(temporary, path);
        }
    }

    sealed class VoskSession : IDisposable
    {
        readonly BlockingCollection<byte[]> audio = new BlockingCollection<byte[]>(80);
        WaveInput input; Thread worker; volatile bool disposed;
        IntPtr spanishModel = IntPtr.Zero, englishModel = IntPtr.Zero;
        IntPtr spanishRecognizer = IntPtr.Zero, englishRecognizer = IntPtr.Zero;
        string lastPartial = "", recentEnglish = ""; DateTime recentEnglishAt = DateTime.MinValue;
        public event Action<string> PartialReceived;
        public event Action<RecognitionResult> TextReceived;
        public event Action<Exception> Failed;

        public void Start(int deviceId)
        {
            if (!Installer.Ready) throw new Exception("Vosk bilingüe no está instalado.");
            NativeMethods.SetDllDirectory(Installer.Engine); VoskNative.vosk_set_log_level(-1);
            spanishModel = NewModel(Installer.SpanishModel); englishModel = NewModel(Installer.EnglishModel);
            if (spanishModel == IntPtr.Zero || englishModel == IntPtr.Zero) throw new Exception("No se pudieron abrir los modelos de idiomas.");
            spanishRecognizer = VoskNative.vosk_recognizer_new(spanishModel, 16000.0f);
            englishRecognizer = VoskNative.vosk_recognizer_new(englishModel, 16000.0f);
            if (spanishRecognizer == IntPtr.Zero || englishRecognizer == IntPtr.Zero) throw new Exception("No se pudieron crear los reconocedores.");
            worker = new Thread(new ThreadStart(ProcessAudio)); worker.IsBackground = true; worker.Name = "Audio Personal - Vosk bilingüe"; worker.Start();
            input = new WaveInput(deviceId); input.DataAvailable += AddAudio; input.Start();
        }

        void AddAudio(byte[] data)
        {
            if (disposed || audio.IsAddingCompleted) return; byte[] ignored;
            if (!audio.TryAdd(data)) { audio.TryTake(out ignored); if (!audio.IsAddingCompleted) audio.TryAdd(data); }
        }

        void ProcessAudio()
        {
            try
            {
                foreach (byte[] buffer in audio.GetConsumingEnumerable())
                {
                    int esEnded = VoskNative.vosk_recognizer_accept_waveform(spanishRecognizer, buffer, buffer.Length);
                    int enEnded = VoskNative.vosk_recognizer_accept_waveform(englishRecognizer, buffer, buffer.Length);
                    if (enEnded != 0)
                    {
                        recentEnglish = ReadJson(VoskNative.vosk_recognizer_result(englishRecognizer), "text"); recentEnglishAt = DateTime.Now;
                    }
                    if (esEnded != 0)
                    {
                        string spanish = ReadJson(VoskNative.vosk_recognizer_result(spanishRecognizer), "text");
                        string english = enEnded != 0 ? recentEnglish : ReadJson(VoskNative.vosk_recognizer_partial_result(englishRecognizer), "partial");
                        if (String.IsNullOrEmpty(english) && (DateTime.Now - recentEnglishAt).TotalSeconds < 3) english = recentEnglish;
                        RaiseText(spanish, english);
                    }
                    else RaisePartial(ReadJson(VoskNative.vosk_recognizer_partial_result(spanishRecognizer), "partial"));
                }
                if (spanishRecognizer != IntPtr.Zero)
                    RaiseText(ReadJson(VoskNative.vosk_recognizer_final_result(spanishRecognizer), "text"),
                        ReadJson(VoskNative.vosk_recognizer_final_result(englishRecognizer), "text"));
            }
            catch (Exception ex)
            {
                if (!disposed) { Action<Exception> handler = Failed; if (handler != null) handler(ex); }
            }
        }

        void RaisePartial(string value)
        {
            if (String.Equals(value, lastPartial, StringComparison.Ordinal)) return; lastPartial = value;
            Action<string> handler = PartialReceived; if (handler != null) handler(value);
        }

        void RaiseText(string spanish, string english)
        {
            if (String.IsNullOrWhiteSpace(spanish)) return;
            Action<RecognitionResult> handler = TextReceived; if (handler != null) handler(new RecognitionResult(spanish, english));
        }

        static string ReadJson(IntPtr pointer, string property)
        {
            string json = Utf8String(pointer);
            Match match = Regex.Match(json, "\\\"" + property + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
            if (!match.Success) return ""; string value = match.Groups[1].Value;
            value = value.Replace("\\n", " ").Replace("\\r", " ").Replace("\\t", " ").Replace("\\\"", "\"").Replace("\\\\", "\\");
            return Regex.Replace(value, "\\s+", " ").Trim();
        }

        static string Utf8String(IntPtr pointer)
        {
            if (pointer == IntPtr.Zero) return ""; int length = 0;
            while (Marshal.ReadByte(pointer, length) != 0) length++;
            byte[] data = new byte[length]; Marshal.Copy(pointer, data, 0, length); return Encoding.UTF8.GetString(data);
        }

        static IntPtr NewModel(string path)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(path + "\0"); IntPtr pointer = Marshal.AllocHGlobal(bytes.Length);
            try { Marshal.Copy(bytes, 0, pointer, bytes.Length); return VoskNative.vosk_model_new(pointer); }
            finally { Marshal.FreeHGlobal(pointer); }
        }

        public void Dispose()
        {
            if (disposed) return; disposed = true;
            if (input != null) { input.Dispose(); input = null; }
            if (!audio.IsAddingCompleted) audio.CompleteAdding();
            bool stopped = worker == null || Thread.CurrentThread == worker || worker.Join(5000);
            if (stopped && spanishRecognizer != IntPtr.Zero) { VoskNative.vosk_recognizer_free(spanishRecognizer); spanishRecognizer = IntPtr.Zero; }
            if (stopped && englishRecognizer != IntPtr.Zero) { VoskNative.vosk_recognizer_free(englishRecognizer); englishRecognizer = IntPtr.Zero; }
            if (stopped && spanishModel != IntPtr.Zero) { VoskNative.vosk_model_free(spanishModel); spanishModel = IntPtr.Zero; }
            if (stopped && englishModel != IntPtr.Zero) { VoskNative.vosk_model_free(englishModel); englishModel = IntPtr.Zero; }
            audio.Dispose();
        }
    }

    sealed class RecognitionResult
    {
        public readonly string Spanish, English;
        public RecognitionResult(string spanish, string english) { Spanish = spanish; English = english; }
    }

    static class VoskNative
    {
        [DllImport("libvosk.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern void vosk_set_log_level(int level);

        [DllImport("libvosk.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr vosk_model_new(IntPtr modelPath);

        [DllImport("libvosk.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern void vosk_model_free(IntPtr model);

        [DllImport("libvosk.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr vosk_recognizer_new(IntPtr model, float sampleRate);

        [DllImport("libvosk.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern int vosk_recognizer_accept_waveform(IntPtr recognizer, byte[] data, int length);

        [DllImport("libvosk.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr vosk_recognizer_result(IntPtr recognizer);

        [DllImport("libvosk.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr vosk_recognizer_partial_result(IntPtr recognizer);

        [DllImport("libvosk.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern IntPtr vosk_recognizer_final_result(IntPtr recognizer);

        [DllImport("libvosk.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern void vosk_recognizer_free(IntPtr recognizer);
    }

    sealed class WaveInput : IDisposable
    {
        const uint CALLBACK_FUNCTION = 0x00030000;
        const uint WIM_DATA = 0x03C0;
        const ushort WAVE_FORMAT_PCM = 1;
        const int BufferCount = 8;
        const int BufferBytes = 3200;
        readonly int deviceId;
        readonly List<WaveBuffer> buffers = new List<WaveBuffer>();
        WaveInCallback callback;
        IntPtr handle = IntPtr.Zero;
        volatile bool running;
        public event Action<byte[]> DataAvailable;

        public WaveInput(int deviceId) { this.deviceId = deviceId; }

        public static IEnumerable<MicrophoneItem> ListDevices()
        {
            uint count = waveInGetNumDevs();
            for (uint index = 0; index < count; index++)
            {
                WAVEINCAPS caps;
                int result = waveInGetDevCaps(new UIntPtr(index), out caps, (uint)Marshal.SizeOf(typeof(WAVEINCAPS)));
                if (result == 0)
                    yield return new MicrophoneItem((int)index, "Dispositivo " + index + " — " + caps.szPname);
            }
        }

        public void Start()
        {
            WAVEFORMATEX format = new WAVEFORMATEX();
            format.wFormatTag = WAVE_FORMAT_PCM;
            format.nChannels = 1;
            format.nSamplesPerSec = 16000;
            format.wBitsPerSample = 16;
            format.nBlockAlign = 2;
            format.nAvgBytesPerSec = 32000;
            format.cbSize = 0;
            callback = Callback;
            uint selected = deviceId < 0 ? UInt32.MaxValue : (uint)deviceId;
            Check(waveInOpen(out handle, selected, ref format, callback, IntPtr.Zero, CALLBACK_FUNCTION),
                "Windows no pudo abrir el micrófono seleccionado");

            for (int i = 0; i < BufferCount; i++)
            {
                WaveBuffer buffer = new WaveBuffer(BufferBytes);
                buffers.Add(buffer);
                Check(waveInPrepareHeader(handle, buffer.Header, (uint)Marshal.SizeOf(typeof(WAVEHDR))),
                    "No se pudo preparar el micrófono");
                Check(waveInAddBuffer(handle, buffer.Header, (uint)Marshal.SizeOf(typeof(WAVEHDR))),
                    "No se pudo iniciar el búfer del micrófono");
            }
            running = true;
            Check(waveInStart(handle), "No se pudo iniciar la grabación");
        }

        void Callback(IntPtr waveIn, uint message, IntPtr instance, IntPtr headerPointer, IntPtr reserved)
        {
            if (message != WIM_DATA || headerPointer == IntPtr.Zero) return;
            WAVEHDR header = (WAVEHDR)Marshal.PtrToStructure(headerPointer, typeof(WAVEHDR));
            if (running && header.dwBytesRecorded > 0)
            {
                byte[] data = new byte[header.dwBytesRecorded];
                Marshal.Copy(header.lpData, data, 0, data.Length);
                Action<byte[]> handler = DataAvailable;
                if (handler != null) handler(data);
            }
            if (running) waveInAddBuffer(handle, headerPointer, (uint)Marshal.SizeOf(typeof(WAVEHDR)));
        }

        static void Check(int result, string message)
        {
            if (result != 0) throw new Exception(message + " (código " + result + ").");
        }

        public void Dispose()
        {
            running = false;
            if (handle != IntPtr.Zero)
            {
                waveInStop(handle);
                waveInReset(handle);
                Thread.Sleep(80);
                foreach (WaveBuffer buffer in buffers)
                    waveInUnprepareHeader(handle, buffer.Header, (uint)Marshal.SizeOf(typeof(WAVEHDR)));
                waveInClose(handle);
                handle = IntPtr.Zero;
            }
            foreach (WaveBuffer buffer in buffers) buffer.Dispose();
            buffers.Clear();
            callback = null;
        }

        sealed class WaveBuffer : IDisposable
        {
            public readonly IntPtr Header;
            readonly IntPtr data;
            public WaveBuffer(int size)
            {
                data = Marshal.AllocHGlobal(size);
                Header = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(WAVEHDR)));
                WAVEHDR value = new WAVEHDR();
                value.lpData = data;
                value.dwBufferLength = (uint)size;
                Marshal.StructureToPtr(value, Header, false);
            }
            public void Dispose()
            {
                Marshal.FreeHGlobal(Header);
                Marshal.FreeHGlobal(data);
            }
        }

        delegate void WaveInCallback(IntPtr waveIn, uint message, IntPtr instance, IntPtr header, IntPtr reserved);

        [StructLayout(LayoutKind.Sequential)]
        struct WAVEFORMATEX
        {
            public ushort wFormatTag;
            public ushort nChannels;
            public uint nSamplesPerSec;
            public uint nAvgBytesPerSec;
            public ushort nBlockAlign;
            public ushort wBitsPerSample;
            public ushort cbSize;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct WAVEHDR
        {
            public IntPtr lpData;
            public uint dwBufferLength;
            public uint dwBytesRecorded;
            public IntPtr dwUser;
            public uint dwFlags;
            public uint dwLoops;
            public IntPtr lpNext;
            public IntPtr reserved;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
        struct WAVEINCAPS
        {
            public ushort wMid;
            public ushort wPid;
            public uint vDriverVersion;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szPname;
            public uint dwFormats;
            public ushort wChannels;
            public ushort wReserved1;
        }

        [DllImport("winmm.dll")] static extern uint waveInGetNumDevs();
        [DllImport("winmm.dll", CharSet = CharSet.Auto)] static extern int waveInGetDevCaps(UIntPtr deviceId, out WAVEINCAPS caps, uint size);
        [DllImport("winmm.dll")] static extern int waveInOpen(out IntPtr waveIn, uint deviceId, ref WAVEFORMATEX format, WaveInCallback callback, IntPtr instance, uint flags);
        [DllImport("winmm.dll")] static extern int waveInPrepareHeader(IntPtr waveIn, IntPtr header, uint size);
        [DllImport("winmm.dll")] static extern int waveInUnprepareHeader(IntPtr waveIn, IntPtr header, uint size);
        [DllImport("winmm.dll")] static extern int waveInAddBuffer(IntPtr waveIn, IntPtr header, uint size);
        [DllImport("winmm.dll")] static extern int waveInStart(IntPtr waveIn);
        [DllImport("winmm.dll")] static extern int waveInStop(IntPtr waveIn);
        [DllImport("winmm.dll")] static extern int waveInReset(IntPtr waveIn);
        [DllImport("winmm.dll")] static extern int waveInClose(IntPtr waveIn);
    }

    sealed class AppSettings
    {
        static readonly string FilePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "AudioPersonal", "musica.ini");
        public string MusicFolder = "", PlayerPath = "";

        public static AppSettings Load()
        {
            AppSettings value = new AppSettings();
            try
            {
                if (!File.Exists(FilePath)) return value;
                foreach (string line in File.ReadAllLines(FilePath, Encoding.UTF8))
                {
                    if (line.StartsWith("MusicFolder=", StringComparison.Ordinal)) value.MusicFolder = line.Substring(12);
                    else if (line.StartsWith("PlayerPath=", StringComparison.Ordinal)) value.PlayerPath = line.Substring(11);
                }
            }
            catch { }
            return value;
        }

        public void Save()
        {
            Directory.CreateDirectory(Path.GetDirectoryName(FilePath));
            File.WriteAllLines(FilePath, new string[] { "MusicFolder=" + MusicFolder, "PlayerPath=" + PlayerPath }, Encoding.UTF8);
        }
    }

    sealed class TrackInfo
    {
        public readonly string Path, Artist, Title, SearchArtist, SearchTitle, SearchIdentity, SearchAll, SpeechArtist, SpeechTitle, SpeechIdentity;
        public TrackInfo(string path, string artist, string title)
        {
            Path = path; Artist = TextTools.CleanUnicode(artist ?? ""); Title = TextTools.CleanUnicode(title ?? "");
            SearchArtist = TextTools.Normalize(Artist); SearchTitle = TextTools.Normalize(Title);
            SearchIdentity = TextTools.Normalize(Artist + " " + Title);
            SearchAll = TextTools.Normalize(Artist + " " + Title + " " + System.IO.Path.GetFileNameWithoutExtension(path) + " " + path);
            SpeechArtist = TextTools.SpeechCompact(SearchArtist); SpeechTitle = TextTools.SpeechCompact(SearchTitle);
            SpeechIdentity = TextTools.SpeechCompact(SearchIdentity);
        }
        public string DisplayName
        {
            get
            {
                if (!String.IsNullOrEmpty(Artist) && !String.IsNullOrEmpty(Title)) return Artist + " — " + Title;
                return System.IO.Path.GetFileNameWithoutExtension(Path);
            }
        }
    }

    sealed class TrackMatch
    {
        public readonly TrackInfo Track; public readonly double Score; public readonly bool ArtistRestricted, ArtistFallback;
        public TrackMatch(TrackInfo track, double score) : this(track, score, false, false) { }
        public TrackMatch(TrackInfo track, double score, bool artistRestricted, bool artistFallback) { Track = track; Score = score; ArtistRestricted = artistRestricted; ArtistFallback = artistFallback; }
    }

    sealed class MusicCatalog
    {
        readonly List<TrackInfo> tracks = new List<TrackInfo>();
        readonly Random random = new Random();
        readonly Dictionary<string, List<int>> searchIndex = new Dictionary<string, List<int>>(StringComparer.Ordinal);
        readonly Dictionary<string, List<int>> speechIndex = new Dictionary<string, List<int>>(StringComparer.Ordinal);
        readonly Dictionary<string, List<int>> artistIndex = new Dictionary<string, List<int>>(StringComparer.Ordinal);
        static readonly string CachePath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "AudioPersonal", "music-index-v2.tsv");
        static readonly string PartialPath = CachePath + ".parcial";
        public int Count { get { return tracks.Count; } }
        public int SkippedCount { get; private set; }

        public void Load(string root, bool rebuild, Action<int, string> report)
        {
            tracks.Clear(); SkippedCount = 0;
            if (!rebuild && LoadCache(root)) { BuildSearchIndex(); report(tracks.Count, "Índice recuperado:"); return; }
            HashSet<string> recovered = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            if (rebuild && File.Exists(PartialPath)) File.Delete(PartialPath);
            if (!rebuild)
            {
                LoadPartial(root, recovered);
                if (recovered.Count == 0 && File.Exists(PartialPath)) File.Delete(PartialPath);
            }
            report(tracks.Count, recovered.Count > 0 ? "Continuando índice recuperado:" : "Buscando archivos de audio...");
            int count = tracks.Count, skipped = 0; bool append = File.Exists(PartialPath);
            Directory.CreateDirectory(Path.GetDirectoryName(PartialPath));
            using (StreamWriter checkpoint = new StreamWriter(PartialPath, append, Encoding.UTF8))
            {
                if (!append) checkpoint.WriteLine("PARTIAL\t" + Encode(root));
                foreach (string file in EnumerateMusic(root))
                {
                    if (recovered.Contains(file)) continue;
                    try
                    {
                        string artist, title; Mp3Tags.Read(file, out artist, out title);
                        TrackInfo track = new TrackInfo(file, artist, title); tracks.Add(track); count++;
                        checkpoint.WriteLine(Encode(track.Path) + "\t" + Encode(track.Artist) + "\t" + Encode(track.Title));
                    }
                    catch { skipped++; }
                    if ((count + skipped) % 200 == 0)
                    {
                        checkpoint.Flush();
                        report(count, skipped == 0 ? "Creando catálogo:" : "Creando catálogo (omitidos " + skipped + "):");
                    }
                }
            }
            SkippedCount = skipped; SaveCache(root); if (File.Exists(PartialPath)) File.Delete(PartialPath); BuildSearchIndex();
            report(tracks.Count, skipped == 0 ? "Catálogo terminado:" : "Catálogo terminado; archivos omitidos " + skipped + ":");
        }

        IEnumerable<string> EnumerateMusic(string root)
        {
            Stack<string> pending = new Stack<string>(); pending.Push(root);
            while (pending.Count > 0)
            {
                string directory = pending.Pop();
                string[] children, files;
                try
                {
                    children = Directory.GetDirectories(directory); files = Directory.GetFiles(directory);
                }
                catch (UnauthorizedAccessException) { continue; }
                catch (IOException) { continue; }
                foreach (string child in children) pending.Push(child);
                foreach (string file in files)
                {
                    string extension = System.IO.Path.GetExtension(file).ToLowerInvariant();
                    if (extension == ".mp3" || extension == ".flac" || extension == ".wav" || extension == ".m4a" ||
                        extension == ".ogg" || extension == ".mp4" || extension == ".wma" || extension == ".aac" || extension == ".opus") yield return file;
                }
            }
        }

        bool LoadCache(string root)
        {
            try
            {
                if (!File.Exists(CachePath)) return false;
                using (StreamReader reader = new StreamReader(CachePath, Encoding.UTF8))
                {
                    string header = reader.ReadLine();
                    if (header != "ROOT\t" + Encode(root)) return false;
                    string line;
                    while ((line = reader.ReadLine()) != null)
                    {
                        string[] fields = line.Split('\t'); if (fields.Length != 3) continue;
                        string path = Decode(fields[0]); tracks.Add(new TrackInfo(path, Decode(fields[1]), Decode(fields[2])));
                    }
                }
                return tracks.Count > 0;
            }
            catch { tracks.Clear(); return false; }
        }

        void LoadPartial(string root, HashSet<string> recovered)
        {
            try
            {
                if (!File.Exists(PartialPath)) return;
                using (StreamReader reader = new StreamReader(PartialPath, Encoding.UTF8))
                {
                    if (reader.ReadLine() != "PARTIAL\t" + Encode(root)) return;
                    string line;
                    while ((line = reader.ReadLine()) != null)
                    {
                        try
                        {
                            string[] fields = line.Split('\t'); if (fields.Length != 3) continue;
                            string path = Decode(fields[0]);
                            if (recovered.Add(path)) tracks.Add(new TrackInfo(path, Decode(fields[1]), Decode(fields[2])));
                        }
                        catch { }
                    }
                }
            }
            catch { tracks.Clear(); recovered.Clear(); }
        }

        void SaveCache(string root)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(CachePath)); string temporary = CachePath + ".nuevo";
            using (StreamWriter writer = new StreamWriter(temporary, false, Encoding.UTF8))
            {
                writer.WriteLine("ROOT\t" + Encode(root));
                foreach (TrackInfo track in tracks)
                    writer.WriteLine(Encode(track.Path) + "\t" + Encode(track.Artist) + "\t" + Encode(track.Title));
            }
            if (File.Exists(CachePath)) File.Delete(CachePath); File.Move(temporary, CachePath);
        }

        static string Encode(string value) { return Convert.ToBase64String(Encoding.UTF8.GetBytes(value ?? "")); }
        static string Decode(string value) { return Encoding.UTF8.GetString(Convert.FromBase64String(value)); }

        public TrackMatch FindBest(string spanish, string english)
        {
            string es = TextTools.ExpandAliases(spanish), en = TextTools.ExpandAliases(english);
            string artist; double artistScore;
            // Cuando el pedido contiene un título que coincide exactamente con el
            // catálogo, resolverlo antes que las similitudes generales. Esto también
            // permite artistas compuestos: "Juan Gabriel" encuentra el archivo cuyo
            // artista es "Juan Gabriel y Cristian Castro".
            TrackMatch exactTitle = FindExactTitleWithArtist(es);
            if (exactTitle != null) return exactTitle;
            if (TryFindArtist(es, out artist, out artistScore)) return FindInsideArtist(es, artist, artistScore);
            double agreement = TextTools.PhraseScore(es, en);
            if (!String.IsNullOrEmpty(en) && agreement >= 0.34 && TryFindArtist(en, out artist, out artistScore)) return FindInsideArtist(en, artist, artistScore);

            TrackMatch spanishMatch = FindGeneral(es);
            if (spanishMatch != null) return spanishMatch;
            // El resultado inglés solo puede decidir cuando se parece fonéticamente a lo
            // entendido en español. Evita saltos como Voyage Voyage -> Owner of a Lonely Heart.
            if (!String.IsNullOrEmpty(en) && agreement >= 0.34) return FindGeneral(en);
            return null;
        }

        TrackMatch FindExactTitleWithArtist(string query)
        {
            if (String.IsNullOrEmpty(query)) return null;
            TrackMatch best = null; double bestArtist = 0;
            foreach (TrackInfo track in tracks)
            {
                string title = track.SearchTitle;
                if (String.IsNullOrEmpty(title)) continue;
                int position = query.IndexOf(title, StringComparison.Ordinal);
                if (position < 0) continue;
                bool leftBoundary = position == 0 || query[position - 1] == ' ';
                int end = position + title.Length;
                bool rightBoundary = end == query.Length || query[end] == ' ';
                if (!leftBoundary || !rightBoundary) continue;
                string artistQuery = (query.Substring(0, position) + " " + query.Substring(end)).Trim();
                if (TextTools.SignificantTokens(artistQuery).Length == 0) continue;
                double artistScore = ArtistPhraseScore(artistQuery, track.SearchArtist);
                if (artistScore < 0.90) continue;
                if (best == null || artistScore > bestArtist + 0.0001)
                {
                    best = new TrackMatch(track, Math.Max(1.0, artistScore), true, false);
                    bestArtist = artistScore;
                }
            }
            return best;
        }

        static double ArtistPhraseScore(string query, string candidate)
        {
            if (String.IsNullOrEmpty(query) || String.IsNullOrEmpty(candidate)) return 0;
            string paddedQuery = " " + query + " ", paddedCandidate = " " + candidate + " ";
            if (paddedQuery.Contains(paddedCandidate)) return 1.0;
            string[] heard = TextTools.SignificantTokens(query), wanted = TextTools.SignificantTokens(candidate);
            if (heard.Length == 0 || wanted.Length == 0) return 0;
            int bestRun = 0;
            for (int start = 0; start < heard.Length; start++)
                for (int candidateStart = 0; candidateStart < wanted.Length; candidateStart++)
                {
                    int run = 0;
                    while (start + run < heard.Length && candidateStart + run < wanted.Length &&
                           TextTools.WordSimilarity(heard[start + run], wanted[candidateStart + run]) >= 0.86) run++;
                    if (run > bestRun) bestRun = run;
                }
            if (bestRun >= 2) return Math.Min(0.98, 0.92 + (bestRun - 2) * 0.02);
            if (wanted.Length == 1 && bestRun == 1) return 0.94;
            return 0;
        }

        TrackMatch FindGeneral(string query)
        {
            if (String.IsNullOrEmpty(query)) return null;
            string speech = TextTools.SpeechCompact(query);
            double best = 0, second = 0; TrackInfo winner = null; string winnerIdentity = null;
            HashSet<int> candidates = new HashSet<int>(); AddCandidates(query, candidates); AddSpeechCandidates(speech, candidates);
            if (candidates.Count == 0) return null;
            foreach (int id in candidates)
            {
                TrackInfo track = tracks[id]; double current = Score(query, speech, track);
                // Varias copias o versiones con el mismo título son equivalentes
                // para un pedido que no especificó artista.
                string identity = track.SearchTitle;
                if (current > best + 0.0001)
                {
                    if (winner != null && identity != winnerIdentity) second = Math.Max(second, best);
                    best = current; winner = track; winnerIdentity = identity;
                }
                else if (identity != winnerIdentity && current > second) second = current;
            }
            // No se reproduce el "menos malo" de toda la biblioteca. Debe existir
            // una coincidencia fuerte y separada del segundo resultado.
            if (winner == null || best < 0.84 || second >= best - 0.055) return null;
            return new TrackMatch(winner, best);
        }

        bool TryFindArtist(string query, out string artist, out double score)
        {
            artist = null; score = 0; double second = 0; bool bestExact = false;
            foreach (string candidate in artistIndex.Keys)
            {
                bool exactPhrase = (" " + query + " ").Contains(" " + candidate + " ");
                double current = exactPhrase ? 1.0 : TextTools.CatalogPhraseScore(query, candidate);
                // Una similitud nunca puede empatar con un nombre pronunciado de forma
                // exacta. Así "Maná" dentro de "Mañana" no vence a "Juan Gabriel".
                if (!exactPhrase) current = candidate.Length <= 3 ? 0 : Math.Min(0.96, current);
                bool better = current > score + 0.0001 || (exactPhrase && (!bestExact || (Math.Abs(current - score) < 0.0001 && (artist == null || candidate.Length > artist.Length))));
                if (better) { second = score; score = current; artist = candidate; bestExact = exactPhrase; }
                else if (current > second) second = current;
            }
            if (score < 0.86 || (score < 0.97 && score - second < 0.045)) { artist = null; return false; }
            return true;
        }

        TrackMatch FindInsideArtist(string query, string artist, double artistScore)
        {
            List<int> ids = artistIndex[artist]; string remainder = RemoveArtist(query, artist);
            if (TextTools.SignificantTokens(remainder).Length == 0) return RandomArtistTrack(ids, artistScore, false);
            double best = 0, second = 0; TrackInfo winner = null; string winnerTitle = null;
            string remainderSpeech = TextTools.SpeechCompact(remainder);
            foreach (int id in ids)
            {
                TrackInfo track = tracks[id];
                double titleScore = TitleScore(remainder, remainderSpeech, track.SearchTitle, track.SpeechTitle);
                if (titleScore > best + 0.0001)
                {
                    if (winner != null && track.SearchTitle != winnerTitle) second = Math.Max(second, best);
                    best = titleScore; winner = track; winnerTitle = track.SearchTitle;
                }
                else if (track.SearchTitle != winnerTitle && titleScore > second) second = titleScore;
            }
            if (winner == null || best < 0.72 || second >= best - 0.06) return null;
            return new TrackMatch(winner, Math.Max(artistScore, best), true, false);
        }

        static double TitleScore(string spoken, string title)
        {
            return TitleScore(spoken, TextTools.SpeechCompact(spoken), title, TextTools.SpeechCompact(title));
        }

        static double TitleScore(string spoken, string spokenSpeech, string title, string titleSpeech)
        {
            if (String.IsNullOrEmpty(spoken) || String.IsNullOrEmpty(title)) return 0;
            if (spoken == title) return 1.0;
            string paddedSpoken = " " + spoken + " ", paddedTitle = " " + title + " ";
            bool titleInsideQuery = paddedSpoken.Contains(paddedTitle);
            bool queryInsideTitle = paddedTitle.Contains(paddedSpoken);
            double score = TextTools.CatalogPhraseScore(spoken, spokenSpeech, title, titleSpeech);
            int spokenWords = WordCount(spoken), titleWords = WordCount(title);
            // Si el título agrega palabras a la consulta ("Nos vemos mañana" frente
            // a "mañana"), no debe empatar con el título exactamente igual. También
            // cubre plurales: "mañana" no debe empatar con "por las mañanas".
            if (queryInsideTitle || spokenWords < titleWords) score = Math.Min(score, 0.74);
            else if (titleInsideQuery) score = Math.Max(score, 0.96);
            return score;
        }

        static int WordCount(string value)
        {
            return value.Split(new char[] { ' ' }, StringSplitOptions.RemoveEmptyEntries).Length;
        }

        TrackMatch RandomArtistTrack(List<int> ids, double artistScore, bool fallback)
        {
            if (ids == null || ids.Count == 0) return null; int selected;
            lock (random) selected = ids[random.Next(ids.Count)];
            return new TrackMatch(tracks[selected], artistScore, true, fallback);
        }

        static string RemoveArtist(string query, string artist)
        {
            int exact = query.IndexOf(artist, StringComparison.Ordinal);
            if (exact >= 0) return (query.Substring(0, exact) + " " + query.Substring(exact + artist.Length)).Trim();
            string[] words = query.Split(new char[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
            int artistWords = Math.Max(1, TextTools.SignificantTokens(artist).Length);
            int bestStart = -1, bestCount = 0; double best = 0;
            for (int start = 0; start < words.Length; start++)
                for (int count = 1; count <= artistWords + 2 && start + count <= words.Length; count++)
                {
                    string fragment = String.Join(" ", words, start, count);
                    double current = TextTools.CatalogPhraseScore(fragment, artist);
                    if (current > best) { best = current; bestStart = start; bestCount = count; }
                }
            if (bestStart >= 0 && best >= 0.84)
            {
                List<string> remaining = new List<string>();
                for (int index = 0; index < words.Length; index++)
                    if (index < bestStart || index >= bestStart + bestCount) remaining.Add(words[index]);
                return String.Join(" ", remaining.ToArray());
            }
            return query;
        }

        void BuildSearchIndex()
        {
            searchIndex.Clear(); speechIndex.Clear(); artistIndex.Clear();
            for (int index = 0; index < tracks.Count; index++)
            {
                HashSet<string> unique = new HashSet<string>(TextTools.SignificantTokens(tracks[index].SearchAll), StringComparer.Ordinal);
                foreach (string token in unique)
                {
                    List<int> values; if (!searchIndex.TryGetValue(token, out values)) { values = new List<int>(); searchIndex[token] = values; }
                    values.Add(index);
                }
                AddSpeechKey(tracks[index].SpeechArtist,index); AddSpeechKey(tracks[index].SpeechTitle,index); AddSpeechKey(tracks[index].SpeechIdentity,index);
                string artist = tracks[index].SearchArtist;
                if (!String.IsNullOrEmpty(artist))
                {
                    List<int> artistTracks; if (!artistIndex.TryGetValue(artist, out artistTracks)) { artistTracks = new List<int>(); artistIndex[artist] = artistTracks; }
                    artistTracks.Add(index);
                }
            }
        }

        void AddSpeechKey(string key,int index)
        {
            if(String.IsNullOrEmpty(key))return;List<int> values;
            if(!speechIndex.TryGetValue(key,out values)){values=new List<int>();speechIndex[key]=values;}
            values.Add(index);
        }

        void AddCandidates(string query, HashSet<int> result)
        {
            foreach (string token in TextTools.SignificantTokens(query))
            {
                List<int> values; if (!searchIndex.TryGetValue(token, out values)) continue;
                foreach (int value in values) result.Add(value);
            }
        }

        void AddSpeechCandidates(string querySpeech,HashSet<int> result)
        {
            if(String.IsNullOrEmpty(querySpeech))return;List<int> values;
            if(!speechIndex.TryGetValue(querySpeech,out values))return;
            foreach(int value in values)result.Add(value);
        }

        IEnumerable<int> AllTrackIds() { for (int index = 0; index < tracks.Count; index++) yield return index; }

        static double Score(string spoken, string spokenSpeech, TrackInfo track)
        {
            if (String.IsNullOrEmpty(spoken)) return 0;
            double artist = TextTools.CatalogPhraseScore(spoken, spokenSpeech, track.SearchArtist, track.SpeechArtist);
            double title = TitleScore(spoken, spokenSpeech, track.SearchTitle, track.SpeechTitle);
            double combined = TextTools.CatalogPhraseScore(spoken, spokenSpeech, track.SearchIdentity, track.SpeechIdentity);
            return Math.Max(Math.Max(title, artist * 0.96), combined);
        }
    }

    static class Mp3Tags
    {
        public static void Read(string path, out string artist, out string title)
        {
            artist = ""; title = "";
            string extension = System.IO.Path.GetExtension(path).ToLowerInvariant();
            if (extension == ".mp3")
            {
                try { ReadV2(path, ref artist, ref title); } catch { }
                if (String.IsNullOrEmpty(artist) || String.IsNullOrEmpty(title)) try { ReadV1(path, ref artist, ref title); } catch { }
            }
            GuessFromName(path, ref artist, ref title);
        }

        static void ReadV2(string path, ref string artist, ref string title)
        {
            using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
            {
                byte[] header = new byte[10]; if (stream.Read(header, 0, 10) != 10 || header[0] != 73 || header[1] != 68 || header[2] != 51) return;
                int version = header[3]; int size = SyncSafe(header, 6); if (size <= 0 || size > 6000000) return;
                int remaining = size; byte[] frameHeader = new byte[10];
                while (remaining >= 10)
                {
                    if (stream.Read(frameHeader, 0, 10) != 10) break; remaining -= 10;
                    string id = Encoding.ASCII.GetString(frameHeader, 0, 4); if (id.Trim('\0').Length == 0) break;
                    int frameSize = version == 4 ? SyncSafe(frameHeader, 4) : BigEndian(frameHeader, 4);
                    if (frameSize <= 0 || frameSize > remaining) break;
                    if ((id == "TPE1" || id == "TIT2") && frameSize <= 65536)
                    {
                        byte[] frame = new byte[frameSize]; int read = 0, amount;
                        while (read < frameSize && (amount = stream.Read(frame, read, frameSize - read)) > 0) read += amount;
                        if (read == frameSize)
                        {
                            if (id == "TPE1") artist = DecodeText(frame, 0, frameSize);
                            else title = DecodeText(frame, 0, frameSize);
                        }
                    }
                    else stream.Seek(frameSize, SeekOrigin.Current);
                    remaining -= frameSize; if (!String.IsNullOrEmpty(artist) && !String.IsNullOrEmpty(title)) break;
                }
            }
        }

        static void ReadV1(string path, ref string artist, ref string title)
        {
            using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
            {
                if (stream.Length < 128) return; stream.Seek(-128, SeekOrigin.End); byte[] tag = new byte[128]; stream.Read(tag, 0, 128);
                if (tag[0] != 84 || tag[1] != 65 || tag[2] != 71) return;
                Encoding latin = Encoding.GetEncoding(28591);
                if (String.IsNullOrEmpty(title)) title = latin.GetString(tag, 3, 30).Trim('\0', ' ');
                if (String.IsNullOrEmpty(artist)) artist = latin.GetString(tag, 33, 30).Trim('\0', ' ');
            }
        }

        static void GuessFromName(string path, ref string artist, ref string title)
        {
            string name = Regex.Replace(System.IO.Path.GetFileNameWithoutExtension(path), "^\\s*\\d{1,3}[ ._-]+", "").Trim();
            string[] parts = Regex.Split(name, "\\s+-\\s+", RegexOptions.None);
            if (parts.Length >= 2)
            {
                if (String.IsNullOrEmpty(artist)) artist = parts[0].Trim();
                if (String.IsNullOrEmpty(title)) title = String.Join(" - ", parts, 1, parts.Length - 1).Trim();
            }
            if (String.IsNullOrEmpty(title)) title = name;
        }

        static int SyncSafe(byte[] value, int offset)
        { return ((value[offset] & 127) << 21) | ((value[offset + 1] & 127) << 14) | ((value[offset + 2] & 127) << 7) | (value[offset + 3] & 127); }
        static int BigEndian(byte[] value, int offset)
        { return (value[offset] << 24) | (value[offset + 1] << 16) | (value[offset + 2] << 8) | value[offset + 3]; }

        static string DecodeText(byte[] value, int offset, int count)
        {
            if (count <= 1) return ""; int encoding = value[offset]; string text;
            if (encoding == 1) text = Encoding.Unicode.GetString(value, offset + 1, count - 1);
            else if (encoding == 2) text = Encoding.BigEndianUnicode.GetString(value, offset + 1, count - 1);
            else if (encoding == 3) text = Encoding.UTF8.GetString(value, offset + 1, count - 1);
            else text = Encoding.GetEncoding(28591).GetString(value, offset + 1, count - 1);
            return text.Trim('\0', '\uFEFF', ' ').Replace("\0", " ").Trim();
        }
    }

    static class TextTools
    {
        static readonly HashSet<string> StopWords = new HashSet<string>(new string[]
        { "de", "del", "la", "el", "los", "las", "una", "un", "the", "a", "an", "and", "y", "por", "para", "cancion", "canción", "musica", "música" });

        public static string Normalize(string value)
        {
            if (String.IsNullOrEmpty(value)) return "";
            string clean = CleanUnicode(value), form;
            try { form = clean.ToLowerInvariant().Normalize(NormalizationForm.FormD); }
            catch { form = clean.ToLowerInvariant(); }
            StringBuilder result = new StringBuilder();
            foreach (char character in form)
                if (System.Globalization.CharUnicodeInfo.GetUnicodeCategory(character) != System.Globalization.UnicodeCategory.NonSpacingMark) result.Append(character);
            string normalized;
            try { normalized = result.ToString().Normalize(NormalizationForm.FormC); }
            catch { normalized = result.ToString(); }
            return Regex.Replace(normalized, "[^a-z0-9 ]", " ").Trim();
        }

        public static string ExpandAliases(string value)
        {
            string normalized=Normalize(value);if(String.IsNullOrEmpty(normalized))return normalized;
            if(Regex.IsMatch(normalized,@"\bredonditos?\b")||Regex.IsMatch(normalized,@"\bredondos?\b.*\b(ricota|ricotta|recota|reporta)\b")||normalized.Contains("los redondos"))normalized+=" patricio rey redonditos ricota";
            if(Regex.IsMatch(normalized,@"\b(bitodas|bitoles|bitels|beatle)\b"))normalized+=" beatles";
            if(normalized.Contains("soda estereo"))normalized+=" soda stereo";
            // Vosk puede dividir o aproximar nombres que no figuran en su vocabulario.
            // Se exige además "tontos" para no corregir frases normales por accidente.
            // Vosk suele devolver varias deformaciones para «gerontocida».
            // La corrección se aplica aunque el artista llegue separado o incompleto.
            if(Regex.IsMatch(normalized,@"\b(geronto|geron|jeron|eron|geronte|gerontoc|gerontosi|gerontoci|que no|no)\s*(cida|sida|tocida|tosida|tocita|sida)\b") ||
               Regex.IsMatch(normalized,@"\b(gerontocida|gerontocita|jerontocida|gerontocida|gerontocida)\b")) normalized+=" gerontocida";
            return Regex.Replace(normalized," +"," ").Trim();
        }

        public static string CleanUnicode(string value)
        {
            if (String.IsNullOrEmpty(value)) return ""; StringBuilder result = new StringBuilder(value.Length);
            for (int index = 0; index < value.Length; index++)
            {
                char character = value[index];
                if (Char.IsHighSurrogate(character))
                {
                    if (index + 1 < value.Length && Char.IsLowSurrogate(value[index + 1]))
                    { result.Append(character); result.Append(value[++index]); }
                    else result.Append(' ');
                }
                else if (Char.IsLowSurrogate(character)) result.Append(' ');
                else result.Append(character);
            }
            return result.ToString();
        }

        public static double PhraseScore(string spoken, string candidate)
        {
            if (String.IsNullOrEmpty(spoken) || String.IsNullOrEmpty(candidate)) return 0;
            if ((" " + spoken + " ").Contains(" " + candidate + " ")) return 1;
            string[] heard = SignificantTokens(spoken), wanted = SignificantTokens(candidate); if (heard.Length == 0 || wanted.Length == 0) return 0;
            double total = 0;
            foreach (string word in wanted)
            {
                double best = 0;
                for (int start = 0; start < heard.Length; start++)
                {
                    string joined = "";
                    // Los nombres propios infrecuentes suelen llegar separados en dos o
                    // tres palabras. Compararlos también unidos usa el catálogo musical
                    // como vocabulario de corrección sin limitar el reconocedor Vosk.
                    for (int count = 0; count < 3 && start + count < heard.Length; count++)
                    {
                        joined += heard[start + count];
                        best = Math.Max(best, WordScore(word, joined));
                    }
                }
                total += best;
            }
            return total / wanted.Length;
        }

        public static double CatalogPhraseScore(string spoken, string candidate)
        {
            return CatalogPhraseScore(spoken, SpeechCompact(spoken), candidate, SpeechCompact(candidate));
        }

        public static double CatalogPhraseScore(string spoken, string spokenSpeech, string candidate, string candidateSpeech)
        {
            if (String.IsNullOrEmpty(spoken) || String.IsNullOrEmpty(candidate)) return 0;
            double lexical = Math.Max(PhraseScore(spoken, candidate), PhraseScore(candidate, spoken));
            if (String.IsNullOrEmpty(spokenSpeech) || String.IsNullOrEmpty(candidateSpeech)) return lexical;
            return Math.Max(lexical, WordScore(spokenSpeech, candidateSpeech));
        }

        public static string SpeechCompact(string value)
        {
            string[] words = SignificantTokens(Normalize(value));
            StringBuilder result = new StringBuilder();
            foreach (string source in words)
            {
                string word = source.Replace("ph", "f").Replace("sh", "ch").Replace("th", "t")
                    .Replace("ee", "i").Replace("ea", "i").Replace("oo", "u")
                    .Replace("qu", "k").Replace("ck", "k").Replace("v", "b")
                    .Replace("y", "i").Replace("z", "s").Replace("j", "").Replace("h", "");
                word = Regex.Replace(word, "(.)\\1+", "$1");
                if (word.Length > 3 && word.EndsWith("s", StringComparison.Ordinal)) word = word.Substring(0, word.Length - 1);
                word = Regex.Replace(word, "[tdsz]$", "x");
                result.Append(word);
            }
            return result.ToString();
        }

        public static string[] SignificantTokens(string value)
        {
            List<string> result = new List<string>();
            foreach (string word in value.Split(new char[] { ' ' }, StringSplitOptions.RemoveEmptyEntries))
                if (word.Length > 1 && !StopWords.Contains(word)) result.Add(word);
            return result.ToArray();
        }

        static double WordScore(string left, string right)
        {
            if (left == right) return 1; int longest = Math.Max(left.Length, right.Length); if (longest == 0) return 1;
            if (left.Contains(right) || right.Contains(left)) return (double)Math.Min(left.Length, right.Length) / longest;
            int[] previous = new int[right.Length + 1], current = new int[right.Length + 1];
            for (int j = 0; j <= right.Length; j++) previous[j] = j;
            for (int i = 1; i <= left.Length; i++)
            {
                current[0] = i;
                for (int j = 1; j <= right.Length; j++)
                    current[j] = Math.Min(Math.Min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (left[i - 1] == right[j - 1] ? 0 : 1));
                int[] swap = previous; previous = current; current = swap;
            }
            return Math.Max(0, 1.0 - ((double)previous[right.Length] / longest));
        }

        public static double WordSimilarity(string left, string right)
        {
            return WordScore(left, right);
        }
    }

    static class MusicRequest
    {
        public static bool HasWake(string heard)
        {
            string value=TextTools.Normalize(heard);int length;return FindWake(value,out length)>=0;
        }

        public static string Extract(string heard, ref DateTime awakeUntil)
        {
            string value = TextTools.Normalize(heard); if (String.IsNullOrEmpty(value)) return null;
            int wakeLength; int wake = FindWake(value, out wakeLength);
            if (wake >= 0) { awakeUntil = DateTime.Now.AddSeconds(12); value = value.Substring(wake + wakeLength).Trim(); }
            else if (DateTime.Now > awakeUntil) return null;
            string[] triggers = { "quiero escuchar", "quiero oir", "quisiera escuchar", "me gustaria escuchar", "reproduce", "reproducir", "reproducime", "poneme", "pone musica", "pon musica", "pon la cancion", "pon el tema", "escuchar" };
            int position = -1, length = 0;
            foreach (string trigger in triggers)
            {
                int found = value.IndexOf(trigger, StringComparison.Ordinal);
                if (found >= 0 && (position < 0 || found < position)) { position = found; length = trigger.Length; }
            }
            if (position < 0 && LooksLikeControlCommand(value)) return null;
            string query = position < 0 ? value : value.Substring(position + length).Trim();
            query = Regex.Replace(query, "^(por favor |una |un |la |el |algo |musica |cancion |tema )+", "");
            query = Regex.Replace(query, " por favor$", "");
            if (query.StartsWith("de ")) query = query.Substring(3);
            if (query.Length < 2) return null; awakeUntil = DateTime.MinValue; return query;
        }

        static bool LooksLikeControlCommand(string value)
        {
            if(String.IsNullOrEmpty(value))return false;
            return CommandProcessor.IsExecutableCommand(value)||Regex.IsMatch(value,@"\b(pausa|pausar|reanuda|reanudar|continua|continuar|siguiente|proxima|cambia|cambiar|anterior|previa|deten|detener|para la musica|parar musica)\b")||
                Regex.IsMatch(value,@"\b(abrir|abre|abri|iniciar|inicia|mostrar|muestra|cerrar|cierra|apagar|apaga|ocultar|oculta)\b.*\breproductor\b");
        }

        static int FindWake(string value, out int length)
        {
            string[] words = { "computadora", "computador" };
            foreach (string word in words) { int index = value.IndexOf(word, StringComparison.Ordinal); if (index >= 0) { length = word.Length; return index; } }
            length = 0; return -1;
        }
    }

    static class PlayerCommandProcessor
    {
        public static string TryExecute(string heard,ref DateTime awakeUntil,InternalPlayerEngine player,Action openPlayer,Action closePlayer)
        {
            string command=TextTools.Normalize(heard);int wakeLength;int wake=FindWake(command,out wakeLength);
            if(wake>=0){awakeUntil=DateTime.Now.AddSeconds(12);command=command.Substring(wake+wakeLength).Trim();}
            else if(DateTime.Now>awakeUntil)return null;
            string result=null;
            bool mentionsPlayer=command.Contains("reproductor")||command.Contains("player");
            if(mentionsPlayer&&Regex.IsMatch(command,@"\b(cerrar|cierra|apagar|apaga|ocultar|oculta)\b")){if(closePlayer!=null)closePlayer();else player.Stop();result="Reproductor apagado.";}
            else if(mentionsPlayer&&Regex.IsMatch(command,@"\b(abrir|abre|abri|iniciar|inicia|mostrar|muestra)\b")){if(openPlayer!=null)openPlayer();result="Reproductor abierto.";}
            else if(command.Contains("pausa")||command.Contains("pausar")){if(player.IsPlaying)player.TogglePlayPause();result="Reproducción en pausa.";}
            else if(command.Contains("reanuda")||command.Contains("reanudar")||command.Contains("continua")||command.Contains("continuar")){if(player.IsPaused)player.TogglePlayPause();result="Continúa la reproducción.";}
            else if(command.Contains("siguiente")||command.Contains("proxima cancion")||command.Contains("otra cancion")||command.Contains("cambiar de cancion")||command.Contains("cambia de cancion")||command.Contains("cambiar la cancion")||command.Contains("cambia la cancion")){player.Next();result="Reproduciendo la canción siguiente.";}
            else if(command.Contains("anterior")||command.Contains("cancion previa")){player.Previous();result="Reproduciendo la canción anterior.";}
            else if(command.Contains("deten la musica")||command.Contains("detener musica")||command.Contains("para la musica")||command.Contains("parar musica")){player.Stop();result="Reproducción detenida.";}
            if(result!=null)awakeUntil=DateTime.MinValue;return result;
        }

        static int FindWake(string value,out int length){string[] words={"computadora","computador"};foreach(string word in words){int index=value.IndexOf(word,StringComparison.Ordinal);if(index>=0){length=word.Length;return index;}}length=0;return -1;}
    }

    static class CommandProcessor
    {
        const string NumberPattern = @"(?:\d{1,3}|cero|cinco|diez|quince|veinte|veinticinco|veinte y cinco|treinta|treinta y cinco|cuarenta|cuarenta y cinco|cincuenta|cincuenta y cinco|sesenta|sesenta y cinco|setenta|setenta y cinco|ochenta|ochenta y cinco|noventa|noventa y cinco|cien)";

        public static bool IsExecutableCommand(string heard)
        {
            string command=Normalize(heard??"");
            return IsMuteCommand(command)||IsAbsoluteVolume(command)||IsRelativeVolume(command);
        }

        public static string TryExecute(string heard, ref DateTime awakeUntil, Action<int> setVolume, Action<int> adjustVolume)
        {
            string normalized = Normalize(heard);
            string wakeWord = "computadora";
            int wake = normalized.IndexOf(wakeWord, StringComparison.Ordinal);
            if (wake < 0) { wakeWord = "computador"; wake = normalized.IndexOf(wakeWord, StringComparison.Ordinal); }
            string command = normalized;
            if (wake >= 0)
            {
                awakeUntil = DateTime.Now.AddSeconds(12);
                command = normalized.Substring(wake + wakeWord.Length).Trim();
                if (command.Length == 0) return "Activada. Espero la orden durante doce segundos.";
            }
            else if (DateTime.Now > awakeUntil) return null;

            if (IsMuteCommand(command))
            {
                NativeMethods.keybd_event(0xAD, 0, 0, UIntPtr.Zero);
                NativeMethods.keybd_event(0xAD, 0, 2, UIntPtr.Zero);
                awakeUntil = DateTime.MinValue;
                return "Orden ejecutada: mute.";
            }

            int percent;
            bool hasPercent = TryReadPercent(command, out percent);
            if(IsAbsoluteVolume(command)&&hasPercent&&setVolume!=null)
            {
                setVolume(percent);awakeUntil=DateTime.MinValue;
                return "Volumen establecido en " + percent + "%.";
            }
            bool down = Regex.IsMatch(command,@"\b(baja|bajar|bajame|disminuye|disminuir|reduce|reducir)\b");
            bool up = Regex.IsMatch(command,@"\b(sube|subir|subime|aumenta|aumentar|incrementa|incrementar)\b");
            if ((!down && !up) || !IsRelativeVolume(command)) return null;
            if (!hasPercent) percent = 10;
            int delta = down ? -percent : percent;
            if(adjustVolume!=null)
            {
                adjustVolume(delta);awakeUntil=DateTime.MinValue;
                return "Orden ejecutada: volumen " + (delta < 0 ? "-" : "+") + percent + "%.";
            }
            int presses = Math.Max(1, (int)Math.Round(percent / 2.0));
            byte key = down ? (byte)0xAE : (byte)0xAF;
            for (int i = 0; i < presses; i++)
            {
                NativeMethods.keybd_event(key, 0, 0, UIntPtr.Zero);
                NativeMethods.keybd_event(key, 0, 2, UIntPtr.Zero);
            }
            awakeUntil = DateTime.MinValue;
            return "Orden ejecutada: volumen " + (down ? "-" : "+") + percent + "% (aproximado).";
        }

        static bool IsMuteCommand(string command)
        {
            return Regex.IsMatch(command,@"^(?:por favor )?(?:mute|silencio|silenciar|activa(?:r)? (?:el )?(?:mute|silencio)|pon(?:er)? (?:el )?mute|quita(?:r)? (?:el )?(?:sonido|audio))(?: por favor)?$");
        }

        static bool IsAbsoluteVolume(string command)
        {
            return Regex.IsMatch(command,@"\b(?:volumen|sonido|audio)\s+(?:a|al|en)\s+(?:un\s+)?"+NumberPattern+@"\b")||
                Regex.IsMatch(command,@"\b(?:pon|pone|poner|establece|establecer|fija|fijar|ajusta|ajustar|deja|dejar)\b.*\b(?:volumen|sonido|audio)\b.*\b"+NumberPattern+@"\b");
        }

        static bool IsRelativeVolume(string command)
        {
            bool direction=Regex.IsMatch(command,@"\b(?:baja|bajar|bajame|disminuye|disminuir|reduce|reducir|sube|subir|subime|aumenta|aumentar|incrementa|incrementar)\b");
            bool target=Regex.IsMatch(command,@"\b(?:volumen|sonido|audio)\b");
            bool explicitPercent=Regex.IsMatch(command,@"\b"+NumberPattern+@"(?:\s*%|\s+por ciento\b)");
            return direction&&(target||explicitPercent);
        }

        static bool TryReadPercent(string value, out int result)
        {
            Match digits = Regex.Match(value, "\\b(\\d{1,3})\\b");
            int parsed;
            if (digits.Success && Int32.TryParse(digits.Groups[1].Value, out parsed)) { result=Math.Max(0,Math.Min(100,parsed));return true; }
            string[] names={"noventa y cinco","ochenta y cinco","setenta y cinco","sesenta y cinco","cincuenta y cinco","cuarenta y cinco","treinta y cinco","veinte y cinco","veinticinco","cien","noventa","ochenta","setenta","sesenta","cincuenta","cuarenta","treinta","veinte","quince","diez","cinco","cero"};
            int[] values={95,85,75,65,55,45,35,25,25,100,90,80,70,60,50,40,30,20,15,10,5,0};
            for(int index=0;index<names.Length;index++)if(Regex.IsMatch(value,@"\b"+Regex.Escape(names[index])+@"\b")){result=values[index];return true;}
            result=0;return false;
        }

        static string Normalize(string value)
        {
            string text = value.ToLowerInvariant();
            string form = text.Normalize(NormalizationForm.FormD);
            StringBuilder result = new StringBuilder();
            foreach (char character in form)
            {
                System.Globalization.UnicodeCategory category = System.Globalization.CharUnicodeInfo.GetUnicodeCategory(character);
                if (category != System.Globalization.UnicodeCategory.NonSpacingMark) result.Append(character);
            }
            return Regex.Replace(result.ToString().Normalize(NormalizationForm.FormC), "[^a-z0-9% ]", " ");
        }
    }

    static class AudioDevices
    {
        public static string GetDefaultCaptureName()
        {
            IMMDevice device = null;
            IPropertyStore store = null;
            object enumeratorObject = null;
            try
            {
                enumeratorObject = new MMDeviceEnumeratorComObject();
                IMMDeviceEnumerator enumerator = (IMMDeviceEnumerator)enumeratorObject;
                Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(EDataFlow.eCapture, ERole.eConsole, out device));
                Marshal.ThrowExceptionForHR(device.OpenPropertyStore(0, out store));
                PROPERTYKEY key = new PROPERTYKEY(new Guid("A45C254E-DF1C-4EFD-8020-67D146A850E0"), 14);
                PROPVARIANT value;
                Marshal.ThrowExceptionForHR(store.GetValue(ref key, out value));
                string name = value.GetString();
                value.Clear();
                return name;
            }
            catch { return ""; }
            finally
            {
                if (store != null) Marshal.ReleaseComObject(store);
                if (device != null) Marshal.ReleaseComObject(device);
                if (enumeratorObject != null) Marshal.ReleaseComObject(enumeratorObject);
            }
        }

        enum EDataFlow { eRender, eCapture, eAll }
        enum ERole { eConsole, eMultimedia, eCommunications }

        [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
        class MMDeviceEnumeratorComObject { }

        [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDeviceEnumerator
        {
            [PreserveSig] int EnumAudioEndpoints(EDataFlow dataFlow, uint stateMask, out IntPtr devices);
            [PreserveSig] int GetDefaultAudioEndpoint(EDataFlow dataFlow, ERole role, out IMMDevice device);
            [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IMMDevice device);
            [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr client);
            [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr client);
        }

        [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDevice
        {
            [PreserveSig] int Activate(ref Guid iid, uint context, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object result);
            [PreserveSig] int OpenPropertyStore(uint access, out IPropertyStore properties);
            [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
            [PreserveSig] int GetState(out uint state);
        }

        [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IPropertyStore
        {
            [PreserveSig] int GetCount(out uint count);
            [PreserveSig] int GetAt(uint index, out PROPERTYKEY key);
            [PreserveSig] int GetValue(ref PROPERTYKEY key, out PROPVARIANT value);
            [PreserveSig] int SetValue(ref PROPERTYKEY key, ref PROPVARIANT value);
            [PreserveSig] int Commit();
        }

        [StructLayout(LayoutKind.Sequential)]
        struct PROPERTYKEY
        {
            public Guid formatId;
            public uint propertyId;
            public PROPERTYKEY(Guid formatId, uint propertyId) { this.formatId = formatId; this.propertyId = propertyId; }
        }

        [StructLayout(LayoutKind.Explicit)]
        public struct PROPVARIANT
        {
            [FieldOffset(0)] public ushort type;
            [FieldOffset(8)] public IntPtr pointer;
            public string GetString() { return type == 31 && pointer != IntPtr.Zero ? Marshal.PtrToStringUni(pointer) : ""; }
            public void Clear() { NativeMethods.PropVariantClear(ref this); }
        }
    }

    static class NativeMethods
    {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern bool SetDllDirectory(string path);

        [DllImport("user32.dll")]
        public static extern void keybd_event(byte virtualKey, byte scanCode, uint flags, UIntPtr extraInfo);

        [DllImport("ole32.dll")]
        public static extern int PropVariantClear(ref AudioDevices.PROPVARIANT value);
    }
}
