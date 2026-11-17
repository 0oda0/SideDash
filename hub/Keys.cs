using System.Runtime.InteropServices;

namespace SideDash.Hub;

/// <summary>Presses a key combo like "Ctrl+Shift+F3" in whatever window has focus (works for any game).
/// Uses scancodes, which games reading raw/DirectInput also see.</summary>
static class Keys2
{
    static readonly Dictionary<string, Keys> Alias = new(StringComparer.OrdinalIgnoreCase)
    {
        ["ctrl"] = Keys.ControlKey, ["control"] = Keys.ControlKey, ["shift"] = Keys.ShiftKey, ["alt"] = Keys.Menu,
        ["win"] = Keys.LWin, ["esc"] = Keys.Escape, ["enter"] = Keys.Return, ["del"] = Keys.Delete, ["space"] = Keys.Space,
        ["0"] = Keys.D0, ["1"] = Keys.D1, ["2"] = Keys.D2, ["3"] = Keys.D3, ["4"] = Keys.D4,
        ["5"] = Keys.D5, ["6"] = Keys.D6, ["7"] = Keys.D7, ["8"] = Keys.D8, ["9"] = Keys.D9,
    };

    /// <summary>Returns false if the combo can't be parsed.</summary>
    public static bool Press(string combo)
    {
        var keys = new List<Keys>();
        foreach (var part in combo.Split('+', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
        {
            if (Alias.TryGetValue(part, out var k) || Enum.TryParse(part, true, out k)) keys.Add(k);
            else return false;
        }
        if (keys.Count == 0) return false;
        var inputs = keys.Select(k => Input(k, false)).Concat(Enumerable.Reverse(keys).Select(k => Input(k, true))).ToArray();
        SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
        return true;
    }

    static INPUT Input(Keys k, bool up)
    {
        ushort scan = (ushort)MapVirtualKey((uint)k, 0);
        uint flags = KEYEVENTF_SCANCODE | (up ? KEYEVENTF_KEYUP : 0);
        // arrows, Insert/Delete/Home/End/PageUp/PageDown, right Ctrl/Alt, Win use the extended prefix
        if (k is Keys.Left or Keys.Right or Keys.Up or Keys.Down or Keys.Insert or Keys.Delete or Keys.Home or Keys.End
                or Keys.PageUp or Keys.PageDown or Keys.LWin or Keys.RWin or Keys.Divide)
            flags |= KEYEVENTF_EXTENDEDKEY;
        return new INPUT { type = 1, ki = new KEYBDINPUT { wScan = scan, dwFlags = flags } };
    }

    const uint KEYEVENTF_EXTENDEDKEY = 0x1, KEYEVENTF_KEYUP = 0x2, KEYEVENTF_SCANCODE = 0x8;

    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }

    // INPUT is a union; MOUSEINPUT is the largest member, so pad the struct to its size
    [StructLayout(LayoutKind.Explicit, Size = 40)]
    struct INPUT { [FieldOffset(0)] public uint type; [FieldOffset(8)] public KEYBDINPUT ki; }

    [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint n, INPUT[] inputs, int size);
    [DllImport("user32.dll")] static extern uint MapVirtualKey(uint code, uint mapType);
}
