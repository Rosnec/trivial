import matplotlib as mpl
import matplotlib.pyplot as plt
import numpy as np

def separate_windows(arr):
    ret = dict()
    for k, v in zip(*arr):
        if k in ret:
            ret[k].append(v)
        else:
            ret[k] = [v]
    return ret

def load_file(filename, *args, **kwargs):
    data = np.genfromtxt(
        filename,
        usecols=(0,1,2),
        dtype=np.float, delimiter='\t',
        skip_header=1,
        *args, **kwargs)
    time_ms, bytes, window_size = data.T
    throughput = bytes*8 / time_ms # kbps
    window_throughput = np.array([window_size, throughput])
    return separate_windows(window_throughput)

def make_plot(throughputs, output, title,
              xlabel="Size", ylabel="Throughput (kbps)",
              *args, **kwargs):
    N = len(throughputs)
    x_offsets = np.arange(N)

    fig, ax = plt.subplots()

    ax.boxplot(list(throughputs.get(k)
                    for k in sorted(throughputs.keys())))
    ax.set_title(title)
    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)
    ax.set_xticklabels(list(sorted(throughputs)))

def finalize(output, *args, **kwargs):
    plt.savefig(output, *args, dpi=500, **kwargs)
    plt.close()
    plt.cla()
              
def plot_data(filename, output, title,
              xlabel="Window Size", ylabel="Throughput (kbps)",
              *args, **kwargs):
    window_to_throughput = load_file(filename)
    labels = list(map(int, sorted(window_to_throughput.keys())))
    N = len(window_to_throughput)
    x_offsets = np.arange(N)

    fig, ax = plt.subplots()

    ax.boxplot(list(window_to_throughput.get(k)
                    for k in labels))
    ax.set_title(title)
    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)
    ax.set_xticklabels(labels)
    finalize(output, *args, **kwargs)

def plot_multi(filenames, output, window, title,
               xlabel="Trial", ylabel="Throughput (kbps)",
               *args, **kwargs):
    all_windows = {fname.split('.')[0] : load_file(fname)
                   for fname in filenames}
    stop_wait = {trial : all_windows[trial][1]
                 for trial in all_windows}
    optimal = {trial : all_windows[trial][window]
               for trial in all_windows}
    trials = sorted(optimal.keys())


    N = len(trials)
    x_offsets = np.arange(N)

    fig, ax = plt.subplots()

    ax.boxplot(list(optimal.get(k)
                    for k in trials))
    plt.setp(ax.boxplot(list(stop_wait.get(k)
                             for k in trials),
                        patch_artist=True)['boxes'],facecolor='cyan',alpha=0.5)
    ax.set_title(title)
    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)
    ax.set_xticklabels(trials)
    finalize(output, *args, **kwargs)
