Bots for the GUI (MicroRTS-GUI.exe / MicroRTS-GUI.jar) in MicroRTS_Adapter

Put bot files in this folder (subfolders are fine), then close and reopen the
GUI. They appear at the end of the Player 0 / Player 1 lists on the States tab,
with the file they came from in brackets. Type in the Search box above a list
to show only the bots whose name contains every word you type.

Only .jar and .ser files are read.

  .jar   Every public bot class in the jar (an ai.core.AI subclass with a
         (UnitTypeTable) or no-argument constructor). Competition bots and
         jars exported by GP (best_bot/final_best_tree_*.jar) both work.
  .ser   Either kind this project writes:
         - A GP tree: bestTree.ser, a population (self_play_final.ser) or a
           GP checkpoint. A population plays its individual with the best
           win rate. Trees must use this project's 7 output cells; 12-cell
           trees from the MicroRTS project are refused (open them with the
           MicroRTS GUI instead).
         - An Adapter-RL model (adapter_final.ser, adapter_latest.ser) or an
           adapter training checkpoint (adapter_runs/<run>/checkpoint.ser).
           It is listed twice: with the adapter, and with the adapter off
           (its base tree alone), to compare the two.
         Large files are only read when you pick them, which can take a while.

Bots in lib/bots/ are listed too.
